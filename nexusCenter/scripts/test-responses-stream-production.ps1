param(
    [string]$Server = "8.218.238.141",
    [string]$SshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519",
    [string]$Endpoint = "https://nexusapi.center/v1/responses"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $PSScriptRoot "log"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$requestId = "req_codex_responses_stream_$([Guid]::NewGuid().ToString('N'))"
$testKeyId = [Guid]::NewGuid().ToString()
$logPath = Join-Path $logDir "responses-stream-production-$((Get-Date).ToString('yyyyMMdd-HHmmss')).log"
$lines = [System.Collections.Generic.List[string]]::new()
function Log([string]$message) { $lines.Add("$([DateTimeOffset]::Now.ToString('o')) $message") }
function Invoke-ProductionSql([string]$Sql) {
    $remote = "docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -F '|' -v ON_ERROR_STOP=1"
    $result = $Sql | & ssh -i $SshKey "root@$Server" $remote
    if ($LASTEXITCODE -ne 0) { throw "production SQL failed: $LASTEXITCODE" }
    return ($result -join "`n").Trim()
}

$configured = $false
$exitCode = 0
$originalSupplierStatus = $null
$originalModelStatus = $null
$groupId = $supplierId = $modelId = $userId = $null
try {
    $row = Invoke-ProductionSql @"
select concat_ws('|', g.id, s.id, m.id, rgs.status, rgm.source_status, u.id)
from routing_groups g
join routing_group_suppliers rgs on rgs.group_id = g.id
join suppliers s on s.id = rgs.supplier_id and s.code = 'hzz'
join ai_models m on m.public_name = 'gpt-5.6-sol'
join routing_group_models rgm on rgm.group_id = g.id and rgm.model_id = m.id
join lateral (
  select candidate.id from users candidate join wallet_accounts wallet on wallet.user_id = candidate.id
  where candidate.status = 'active' and wallet.permanent_credits + wallet.expiring_credits > 1
    and not exists (select 1 from subscriptions subscription where subscription.user_id = candidate.id
      and subscription.status = 'active' and subscription.expires_at > now())
  order by wallet.permanent_credits + wallet.expiring_credits desc limit 1
) u on true
where g.code = 'hzz_fl' and g.status = 'active'
  and exists (select 1 from routing_group_supplier_credentials credential
    where credential.group_id = g.id and credential.supplier_id = s.id
      and credential.status = 'active' and credential.encrypted_credential is not null);
"@
    $parts = $row.Split('|')
    if ($parts.Count -ne 6) { throw "hzz_fl route is incomplete" }
    $groupId, $supplierId, $modelId, $originalSupplierStatus, $originalModelStatus, $userId = $parts

    $hmacKey = (& ssh -i $SshKey "root@$Server" "docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1").Trim()
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $apiKey = "sk-nx-v1_" + [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    $hmac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($hmacKey))
    try { $hash = ([BitConverter]::ToString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("api-key:v1:$apiKey"))) -replace '-', '').ToLowerInvariant() } finally { $hmac.Dispose() }
    $prefix = $apiKey.Substring(0,16); $suffix = $apiKey.Substring($apiKey.Length-8)
    Invoke-ProductionSql @"
begin;
update routing_group_suppliers set status='active',updated_at=now(),version=version+1 where group_id='$groupId'::uuid and supplier_id='$supplierId'::uuid;
update routing_group_models set source_status='active',updated_at=now(),version=version+1 where group_id='$groupId'::uuid and model_id='$modelId'::uuid;
insert into api_keys (id,user_id,name,key_prefix,key_suffix,key_hash,key_hash_version,status,default_group_id,service_group_id,allowed_model_ids,allowed_group_ids,ip_allowlist,rpm_limit,tpm_limit,concurrency_limit,credit_limit,expires_at)
values ('$testKeyId'::uuid,'$userId'::uuid,'Codex Responses SSE 冒烟测试','$prefix','$suffix',decode('$hash','hex'),1,'active','$groupId'::uuid,'$groupId'::uuid,'["$modelId"]'::jsonb,'["$groupId"]'::jsonb,'[]'::jsonb,5,10000,1,1,now()+interval '15 minutes');
commit;
"@ | Out-Null
    $configured = $true

    $body = @{ model='gpt-5.6-sol'; input='只回复：Responses SSE 测试成功'; stream=$true } | ConvertTo-Json -Compress
    Log "TEST request_id=$requestId endpoint=$Endpoint model=gpt-5.6-sol"
    $response = Invoke-WebRequest -Uri $Endpoint -Method Post -Headers @{ Authorization="Bearer $apiKey"; 'X-Request-Id'=$requestId } -ContentType 'application/json' -Body $body -TimeoutSec 180
    Log "HTTP status=$($response.StatusCode) content_type=$($response.Headers['Content-Type']) bytes=$([Text.Encoding]::UTF8.GetByteCount($response.Content))"
    Log "EVENTS completed=$($response.Content.Contains('response.completed')) failed=$($response.Content.Contains('response.failed'))"
    if ($response.StatusCode -ne 200) { throw "expected HTTP 200, got $($response.StatusCode)" }
    if (-not $response.Content.Contains('response.completed')) { throw 'response.completed was not returned' }

    $log = ''
    for ($i=0; $i -lt 15 -and [string]::IsNullOrWhiteSpace($log); $i++) {
        Start-Sleep -Seconds 1
        $log = Invoke-ProductionSql "select concat_ws('|',status_code,coalesce(platform_error_code,''),public_model,streaming) from request_logs where request_id='$requestId';"
    }
    Log "REQUEST_LOG $log"
    if ($log -notmatch '^200\|\|gpt-5\.6-sol\|t$') { throw "request log validation failed: $log" }
    Log 'PASS response.completed returned and request log settled as HTTP 200'
}
catch { Log "FAIL $($_.Exception.Message)"; $exitCode = 1 }
finally {
    if ($configured) {
        try {
            Invoke-ProductionSql @"
begin;
update api_keys set status='revoked',revoked_at=now(),status_changed_at=now(),version=version+1 where id='$testKeyId'::uuid;
update routing_group_suppliers set status='$originalSupplierStatus',updated_at=now(),version=version+1 where group_id='$groupId'::uuid and supplier_id='$supplierId'::uuid;
update routing_group_models set source_status='$originalModelStatus',updated_at=now(),version=version+1 where group_id='$groupId'::uuid and model_id='$modelId'::uuid;
commit;
"@ | Out-Null
            Log 'CLEANUP temporary key revoked and route statuses restored'
        } catch { Log "CLEANUP_FAIL $($_.Exception.Message)"; $exitCode = 1 }
    }
    $lines | Set-Content -LiteralPath $logPath -Encoding UTF8
}
Get-Content -LiteralPath $logPath
exit $exitCode
