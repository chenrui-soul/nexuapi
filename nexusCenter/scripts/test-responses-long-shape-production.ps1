param(
    [string]$Server = "8.218.238.141",
    [string]$SshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519",
    [string]$Endpoint = "https://nexusapi.center/v1/responses"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$casePath = Join-Path $projectRoot "references\responses-long-shape-cases.json"
$logDir = Join-Path $PSScriptRoot "log"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$case = Get-Content -Raw -LiteralPath $casePath | ConvertFrom-Json

function Invoke-ProductionSql([string]$Sql) {
    $remote = "docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -F '|' -v ON_ERROR_STOP=1"
    $result = $Sql | & ssh -i $SshKey "root@$Server" $remote
    if ($LASTEXITCODE -ne 0) { throw "production SQL failed: $LASTEXITCODE" }
    ($result -join "`n").Trim()
}

$logLines = [System.Collections.Generic.List[string]]::new()
function Log([string]$Message) { $logLines.Add("$([DateTimeOffset]::Now.ToString('o')) $Message") }

$keyId = [Guid]::NewGuid().ToString()
$requestId = "req_responses_long_shape_$([Guid]::NewGuid().ToString('N'))"
$fixturePath = Join-Path $env:TEMP "$requestId.json"
$responsePath = Join-Path $env:TEMP "$requestId.sse"
$created = $false
$exitCode = 0
try {
    $row = Invoke-ProductionSql @"
select concat_ws('|',g.id,m.id,u.id)
from routing_groups g
join ai_models m on m.public_name='$($case.model)' and m.status='active'
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
    try {
        $hash = ([BitConverter]::ToString($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes("api-key:v1:$apiKey"))) -replace '-', '').ToLowerInvariant()
    } finally {
        $mac.Dispose()
    }
    $prefix = $apiKey.Substring(0,16)
    $suffix = $apiKey.Substring($apiKey.Length-8)
    Invoke-ProductionSql @"
insert into api_keys (id,user_id,name,key_prefix,key_suffix,key_hash,key_hash_version,status,default_group_id,service_group_id,allowed_model_ids,allowed_group_ids,ip_allowlist,rpm_limit,tpm_limit,concurrency_limit,credit_limit,expires_at)
values ('$keyId'::uuid,'$userId'::uuid,'Responses long-shape diagnostic','$prefix','$suffix',decode('$hash','hex'),1,'active','$groupId'::uuid,'$groupId'::uuid,'["$modelId"]'::jsonb,'["$groupId"]'::jsonb,'[]'::jsonb,10,1000000,1,5,now()+interval '10 minutes');
"@ | Out-Null
    $created = $true

    $baseLength = [Math]::Floor([double]$case.target_input_chars / [double]$case.input_items)
    $remainder = [int]$case.target_input_chars - ([int]$baseLength * [int]$case.input_items)
    $items = [System.Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt [int]$case.input_items; $index++) {
        $length = [int]$baseLength + $(if ($index -lt $remainder) { 1 } else { 0 })
        $prefixText = "Item $index. "
        $repeatCount = [Math]::Max(0, [Math]::Ceiling(($length - $prefixText.Length) / 4.0))
        $text = ($prefixText + ("the " * $repeatCount))
        if ($text.Length -gt $length) { $text = $text.Substring(0, $length) }
        if ($text.Length -lt $length) { $text += "x" * ($length - $text.Length) }
        $items.Add(@{
            type = "message"
            role = "user"
            content = @(@{ type = "input_text"; text = $text })
        })
    }
    $payload = [ordered]@{
        model = [string]$case.model
        input = $items
        max_output_tokens = [int]$case.max_output_tokens
        stream = $true
    }
    $payload | ConvertTo-Json -Depth 8 -Compress | Set-Content -LiteralPath $fixturePath -Encoding utf8NoBOM
    $requestBytes = (Get-Item -LiteralPath $fixturePath).Length
    Log "TEST request_id=$requestId input_items=$($case.input_items) input_chars=$($case.target_input_chars) request_bytes=$requestBytes"

    $statusText = (& curl.exe --silent --show-error --max-time 600 --request POST `
        --header "Authorization: Bearer $apiKey" `
        --header "X-Request-Id: $requestId" `
        --header "Content-Type: application/json" `
        --data-binary "@$fixturePath" `
        --output $responsePath --write-out "%{http_code}" $Endpoint 2>&1) -join ''
    $curlExit = $LASTEXITCODE
    $content = if (Test-Path -LiteralPath $responsePath) { Get-Content -Raw -LiteralPath $responsePath } else { '' }
    $status = if ($statusText -match '([0-9]{3})$') { [int]$Matches[1] } else { 0 }
    $completed = $content.Contains([string]$case.expected_terminal_event)
    $failed = $content.Contains('response.failed')
    Log "HTTP status=$status curl_exit=$curlExit response_bytes=$([Text.Encoding]::UTF8.GetByteCount($content)) completed=$completed failed=$failed"
    if ($status -ne [int]$case.expected_http_status -or $curlExit -ne 0 -or -not $completed -or $failed) {
        throw "long-shape response failed"
    }

    Start-Sleep -Seconds 2
    $requestLog = Invoke-ProductionSql "select concat_ws('|',status_code,retry_count,duration_ms,request_payload_size,coalesce(response_summary->>'input_tokens',''),coalesce(response_summary->>'cached_tokens','')) from request_logs where request_id='$requestId';"
    $attempts = Invoke-ProductionSql "select concat_ws('|',attempt_no,outcome,coalesce(error_category,''),coalesce(error_summary,''),duration_ms) from upstream_attempt_logs where request_id='$requestId' order by attempt_no;"
    $settlements = Invoke-ProductionSql "select count(*) from wallet_reservations where request_id='$requestId' and status='settled';"
    Log "REQUEST_LOG $requestLog"
    foreach ($line in ($attempts -split "`n")) { if ($line) { Log "ATTEMPT $line" } }
    Log "SETTLEMENT_COUNT $settlements"
    if ([int]$settlements -ne [int]$case.expected_settlement_count) { throw "settlement count mismatch" }
    Log "PASS long request shape completed once and settled once"
} catch {
    Log "FAIL $($_.Exception.Message)"
    $exitCode = 1
} finally {
    if ($created) {
        try {
            Invoke-ProductionSql "update api_keys set status='revoked',revoked_at=now(),status_changed_at=now(),version=version+1 where id='$keyId'::uuid;" | Out-Null
            Log "CLEANUP temporary API key revoked"
        } catch {
            Log "CLEANUP_FAIL $($_.Exception.Message)"
            $exitCode = 1
        }
    }
    Remove-Item -LiteralPath $fixturePath,$responsePath -Force -ErrorAction SilentlyContinue
    $logPath = Join-Path $logDir "responses-long-shape-production-$((Get-Date).ToString('yyyyMMdd-HHmmss')).log"
    $logLines | Set-Content -LiteralPath $logPath -Encoding utf8NoBOM
    $logLines | ForEach-Object { Write-Output $_ }
}
if ($exitCode) { exit $exitCode }
