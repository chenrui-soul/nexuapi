param(
    [int]$RequestCount = 12,
    [string]$Server = "8.218.238.141",
    [string]$SshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519",
    [string]$Endpoint = "https://nexusapi.center/v1/responses"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$referenceDir = Join-Path $projectRoot "references"
$logDir = Join-Path $PSScriptRoot "log"
New-Item -ItemType Directory -Force -Path $referenceDir, $logDir | Out-Null
$fixturePath = Join-Path $referenceDir "responses-ha.request.json"
@{ model = "gpt-5.6-sol"; input = "只回复：Responses HA OK"; stream = $true } |
    ConvertTo-Json -Compress | Set-Content -LiteralPath $fixturePath -Encoding UTF8
$requestBody = Get-Content -Raw -LiteralPath $fixturePath

function Invoke-ProductionSql([string]$Sql) {
    $remote = "docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -F '|' -v ON_ERROR_STOP=1"
    $result = $Sql | & ssh -i $SshKey "root@$Server" $remote
    if ($LASTEXITCODE -ne 0) { throw "production SQL failed: $LASTEXITCODE" }
    ($result -join "`n").Trim()
}

$logLines = [System.Collections.Generic.List[string]]::new()
function Log([string]$Message) { $logLines.Add("$([DateTimeOffset]::Now.ToString('o')) $Message") }
$keyId = [Guid]::NewGuid().ToString()
$created = $false
$exitCode = 0
try {
    $row = Invoke-ProductionSql @"
select concat_ws('|',g.id,m.id,u.id)
from routing_groups g
join ai_models m on m.public_name='gpt-5.6-sol' and m.status='active'
join lateral (
  select candidate.id from users candidate join wallet_accounts w on w.user_id=candidate.id
  where candidate.status='active' and w.permanent_credits+w.expiring_credits>100
  order by w.permanent_credits+w.expiring_credits desc limit 1
) u on true
where g.code='gpt_tj' and g.status='active';
"@
    $groupId,$modelId,$userId = $row.Split('|')
    $hmacKey = (& ssh -i $SshKey "root@$Server" "docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1").Trim()
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $apiKey = "sk-nx-v1_" + [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    $mac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($hmacKey))
    try { $hash = ([BitConverter]::ToString($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes("api-key:v1:$apiKey"))) -replace '-', '').ToLowerInvariant() } finally { $mac.Dispose() }
    $prefix = $apiKey.Substring(0,16); $suffix = $apiKey.Substring($apiKey.Length-8)
    Invoke-ProductionSql @"
insert into api_keys (id,user_id,name,key_prefix,key_suffix,key_hash,key_hash_version,status,default_group_id,service_group_id,allowed_model_ids,allowed_group_ids,ip_allowlist,rpm_limit,tpm_limit,concurrency_limit,credit_limit,expires_at)
values ('$keyId'::uuid,'$userId'::uuid,'Codex Responses HA 测试','$prefix','$suffix',decode('$hash','hex'),1,'active','$groupId'::uuid,'$groupId'::uuid,'["$modelId"]'::jsonb,'["$groupId"]'::jsonb,'[]'::jsonb,120,1000000,${RequestCount},5,now()+interval '15 minutes');
"@ | Out-Null
    $created = $true
    $requests = 1..$RequestCount | ForEach-Object { [pscustomobject]@{ n=$_; id="req_responses_ha_$([Guid]::NewGuid().ToString('N'))" } }
    $results = @($requests | ForEach-Object -Parallel {
        $responsePath = Join-Path $env:TEMP ("nexus-responses-" + $_.id + ".sse")
        try {
            $curlArgs = @(
                '--silent', '--show-error', '--max-time', '600', '--request', 'POST',
                '--header', "Authorization: Bearer $using:apiKey",
                '--header', "X-Request-Id: $($_.id)",
                '--header', 'Content-Type: application/json',
                '--data-binary', $using:requestBody,
                '--output', $responsePath, '--write-out', '%{http_code}', $using:Endpoint
            )
            $statusText = (& curl.exe @curlArgs 2>&1) -join ''
            $curlExit = $LASTEXITCODE
            $content = if (Test-Path -LiteralPath $responsePath) { Get-Content -Raw -LiteralPath $responsePath } else { '' }
            $status = if ($statusText -match '([0-9]{3})$') { [int]$Matches[1] } else { 0 }
            [pscustomobject]@{
                n=$_.n; id=$_.id; status=$status; curl_exit=$curlExit
                bytes=[Text.Encoding]::UTF8.GetByteCount($content)
                created=$content.Contains('response.created'); completed=$content.Contains('response.completed')
                failed=$content.Contains('response.failed'); content=$content
                head=$content.Substring(0,[Math]::Min(180,$content.Length))
                tail=$content.Substring([Math]::Max(0,$content.Length-180))
            }
        } catch {
            [pscustomobject]@{ n=$_.n; id=$_.id; status=0; curl_exit=-1; completed=$false; failed=$false; error=$_.Exception.Message }
        } finally {
            Remove-Item -LiteralPath $responsePath -Force -ErrorAction SilentlyContinue
        }
    } -ThrottleLimit $RequestCount)
    foreach ($r in $results | Sort-Object n) {
        Log "HTTP n=$($r.n) request_id=$($r.id) status=$($r.status) curl_exit=$($r.curl_exit) bytes=$($r.bytes) created=$($r.created) completed=$($r.completed) failed=$($r.failed) error=$($r.error) head=$($r.head) tail=$($r.tail)"
        if ($r.status -ne 200 -or -not $r.completed -or $r.failed) {
            $bodyPath = Join-Path $logDir "responses-ha-$($r.id).sse"
            Set-Content -LiteralPath $bodyPath -Value $r.content -Encoding UTF8
            Log "FAILED_BODY $bodyPath"
        }
    }
    if (@($results | Where-Object { $_.status -ne 200 -or -not $_.completed -or $_.failed }).Count) { throw 'one or more concurrent Responses streams failed' }
    $ids = ($requests.id | ForEach-Object { "'$_'" }) -join ','
    Start-Sleep -Seconds 2
    $rows = Invoke-ProductionSql "select l.request_id,l.status_code,coalesce(s.code,''),l.streaming from request_logs l left join suppliers s on s.id=l.supplier_id where l.request_id in ($ids) order by l.request_id;"
    $attempts = Invoke-ProductionSql "select a.request_id,a.attempt_no,s.code,a.outcome,coalesce(a.upstream_status::text,'') from upstream_attempt_logs a join suppliers s on s.id=a.supplier_id where a.request_id in ($ids) order by a.request_id,a.attempt_no;"
    foreach ($line in ($rows -split "`n")) { if ($line) { Log "REQUEST_LOG $line" } }
    foreach ($line in ($attempts -split "`n")) { if ($line) { Log "ATTEMPT $line" } }
    $recordedCount = @($rows -split "`n" | Where-Object { $_ }).Count
    if ($recordedCount -ne $RequestCount) { throw 'request log count mismatch' }
    Log 'PASS all concurrent Responses streams completed and settled'
} catch { Log "FAIL $($_.Exception.Message)"; $exitCode = 1 }
finally {
    if ($created) {
        try { Invoke-ProductionSql "update api_keys set status='revoked',revoked_at=now(),status_changed_at=now(),version=version+1 where id='$keyId'::uuid;" | Out-Null; Log 'CLEANUP temporary API key revoked' }
        catch { Log "CLEANUP_FAIL $($_.Exception.Message)"; $exitCode = 1 }
    }
    $path = Join-Path $logDir "responses-ha-production-$((Get-Date).ToString('yyyyMMdd-HHmmss')).log"
    $logLines | Set-Content -LiteralPath $path -Encoding UTF8
    $logLines | ForEach-Object { Write-Output $_ }
}
if ($exitCode) { exit $exitCode }
