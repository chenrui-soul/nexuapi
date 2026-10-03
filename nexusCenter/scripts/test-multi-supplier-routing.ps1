param(
    [string]$Server = "8.218.238.141",
    [string]$SshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519",
    [string]$Endpoint = "https://nexusapi.center/v1/chat/completions"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$referenceDir = Join-Path $projectRoot "references"
$logDir = Join-Path $PSScriptRoot "log"
New-Item -ItemType Directory -Force -Path $referenceDir, $logDir | Out-Null

$fixturePath = Join-Path $referenceDir "multi-supplier-chat.request.json"
$fixture = [ordered]@{
    model = "gpt-5.6-sol"
    messages = @(
        [ordered]@{
            role = "user"
            content = "请只回复：OK"
        }
    )
    stream = $false
    max_tokens = 16
}
$fixture | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8NoBOM $fixturePath
$requestBody = Get-Content -Raw -Encoding utf8 $fixturePath

function Invoke-ProductionSql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $remoteCommand = "docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -F '|' -v ON_ERROR_STOP=1"
    $result = $Sql | & ssh -i $SshKey "root@$Server" $remoteCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Production SQL command failed with exit code $LASTEXITCODE"
    }
    return ($result -join "`n").Trim()
}

function Add-TestLog {
    param([string]$Message)
    $script:logLines.Add("$([DateTimeOffset]::Now.ToString('o')) $Message")
}

$logLines = [System.Collections.Generic.List[string]]::new()
$testKeyId = [Guid]::NewGuid().ToString()
$requestId = "req_multi_supplier_$([Guid]::NewGuid().ToString('N'))"
$testKeyCreated = $false
$exitCode = 0

try {
    $configuration = Invoke-ProductionSql @"
select concat_ws('|', g.id, m.id, test_user.id, count(distinct s.id))
from routing_groups g
join routing_group_models rgm
  on rgm.group_id = g.id and rgm.source_status = 'active'
join ai_models m
  on m.id = rgm.model_id and m.public_name = 'gpt-5.6-sol' and m.status = 'active'
join routing_group_suppliers rgs
  on rgs.group_id = g.id and rgs.status = 'active'
join routing_group_supplier_credentials credential
  on credential.group_id = g.id and credential.supplier_id = rgs.supplier_id
 and credential.status = 'active' and credential.encrypted_credential is not null
join suppliers s
  on s.id = rgs.supplier_id and s.status = 'active' and s.health_status != 'unavailable'
join channels c
  on c.supplier_id = s.id and c.operation_code = 'chat_completions'
 and c.status in ('active', 'degraded')
join lateral (
    select candidate.id
    from users candidate
    join wallet_accounts wallet on wallet.user_id = candidate.id
    where candidate.status = 'active'
      and wallet.permanent_credits + wallet.expiring_credits > 1
      and not exists (
          select 1 from subscriptions subscription
          where subscription.user_id = candidate.id
            and subscription.status = 'active'
            and subscription.expires_at > now()
      )
    order by wallet.permanent_credits + wallet.expiring_credits desc
    limit 1
) test_user on true
where g.code = 'gpt_tj' and g.status = 'active'
group by g.id, m.id, test_user.id;
"@

    $parts = $configuration.Split('|')
    if ($parts.Count -ne 4) {
        throw "The gpt_tj production route configuration is incomplete"
    }
    $groupId, $modelId, $userId, $candidateCount = $parts
    if ([int]$candidateCount -lt 2) {
        throw "Expected at least two eligible suppliers but found $candidateCount"
    }
    Add-TestLog "CONFIG group=gpt_tj model=gpt-5.6-sol eligible_suppliers=$candidateCount"

    $hmacKeyBase64 = (& ssh -i $SshKey "root@$Server" "docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1").Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($hmacKeyBase64)) {
        throw "Unable to load API key HMAC configuration"
    }

    $randomBytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $payload = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $apiKey = "sk-nx-v1_$payload"
    $keyPrefix = $apiKey.Substring(0, 16)
    $keySuffix = $apiKey.Substring($apiKey.Length - 8)

    $hmac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($hmacKeyBase64))
    try {
        $message = [Text.Encoding]::UTF8.GetBytes("api-key:v1:$apiKey")
        $keyHashHex = [Convert]::ToHexString($hmac.ComputeHash($message)).ToLowerInvariant()
    }
    finally {
        $hmac.Dispose()
    }

    Invoke-ProductionSql @"
insert into api_keys (
    id, user_id, name, key_prefix, key_suffix, key_hash, key_hash_version,
    status, default_group_id, service_group_id, allowed_model_ids,
    allowed_group_ids, ip_allowlist, rpm_limit, tpm_limit,
    concurrency_limit, credit_limit, expires_at
) values (
    '$testKeyId'::uuid, '$userId'::uuid, 'Codex 多供应商选路测试',
    '$keyPrefix', '$keySuffix', decode('$keyHashHex', 'hex'), 1,
    'active', '$groupId'::uuid, '$groupId'::uuid,
    '["$modelId"]'::jsonb, '["$groupId"]'::jsonb, '[]'::jsonb,
    5, 10000, 1, 1, now() + interval '15 minutes'
);
"@ | Out-Null
    $testKeyCreated = $true

    Add-TestLog "REQUEST request_id=$requestId endpoint=$Endpoint"
    $response = Invoke-WebRequest `
        -Uri $Endpoint `
        -Method Post `
        -Headers @{ Authorization = "Bearer $apiKey"; "X-Request-Id" = $requestId } `
        -ContentType "application/json" `
        -Body $requestBody `
        -TimeoutSec 180 `
        -SkipHttpErrorCheck

    Add-TestLog "HTTP status=$($response.StatusCode)"
    Add-TestLog "RESPONSE $($response.Content)"

    if ($response.StatusCode -ne 200) {
        throw "Expected HTTP 200 but received $($response.StatusCode)"
    }

    $attemptData = ""
    for ($poll = 0; $poll -lt 15 -and [string]::IsNullOrWhiteSpace($attemptData); $poll++) {
        Start-Sleep -Seconds 1
        $attemptData = Invoke-ProductionSql @"
select concat_ws('|', attempt.attempt_no, supplier.code, attempt.outcome,
                     coalesce(attempt.upstream_status::text, ''))
from upstream_attempt_logs attempt
join suppliers supplier on supplier.id = attempt.supplier_id
where attempt.request_id = '$requestId'
order by attempt.attempt_no;
"@
    }

    $attemptRows = @($attemptData -split "`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($row in $attemptRows) {
        Add-TestLog "UPSTREAM_ATTEMPT $row"
    }
    if ($attemptRows.Count -ne 1) {
        throw "Expected exactly one upstream call after a successful first attempt, found $($attemptRows.Count)"
    }

    $attempt = $attemptRows[0].Split('|')
    if ($attempt.Count -lt 3 -or $attempt[1] -ne 'hzz' -or $attempt[2] -ne 'success') {
        throw "Unexpected first upstream result: $($attemptRows[0])"
    }

    Add-TestLog "PASS configured_suppliers=$candidateCount actual_upstream_calls=$($attemptRows.Count) selected_supplier=$($attempt[1])"
}
catch {
    $exitCode = 1
    Add-TestLog "FAIL $($_.Exception.Message)"
}
finally {
    if ($testKeyCreated) {
        try {
            Invoke-ProductionSql @"
update api_keys
set status = 'revoked', revoked_at = now(), status_changed_at = now(), version = version + 1
where id = '$testKeyId'::uuid;
"@ | Out-Null
            Add-TestLog "CLEANUP temporary API key revoked"
        }
        catch {
            $exitCode = 1
            Add-TestLog "CLEANUP_FAIL $($_.Exception.Message)"
        }
    }
    if (Get-Variable -Name apiKey -ErrorAction SilentlyContinue) {
        $apiKey = $null
    }
    if (Get-Variable -Name hmacKeyBase64 -ErrorAction SilentlyContinue) {
        $hmacKeyBase64 = $null
    }
}

$logPath = Join-Path $logDir "multi-supplier-$requestId.log"
$logLines | Set-Content -Encoding utf8NoBOM $logPath
$logLines | ForEach-Object { Write-Output $_ }

if ($exitCode -ne 0) {
    throw "Multi-supplier production routing test failed. See $logPath"
}

Write-Output "Log: $logPath"
