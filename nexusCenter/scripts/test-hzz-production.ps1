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

$fixturePath = Join-Path $referenceDir "hzz-chat-completions.request.json"
$fixture = [ordered]@{
    model = "gpt-5.6-sol"
    messages = @(
        [ordered]@{
            role = "user"
            content = "请只回复：红蜘蛛生产链路测试成功"
        }
    )
    stream = $false
    max_tokens = 64
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
$requestId = "req_codex_hzz_$([Guid]::NewGuid().ToString('N'))"
$originalSupplierStatus = $null
$originalModelStatus = $null
$testConfigured = $false
$exitCode = 0

try {
    $configuration = Invoke-ProductionSql @"
select concat_ws('|',
    g.id,
    s.id,
    m.id,
    rgs.status,
    rgm.source_status,
    u.id
)
from routing_groups g
join routing_group_suppliers rgs on rgs.group_id = g.id
join suppliers s on s.id = rgs.supplier_id and s.code = 'hzz'
join ai_models m on m.public_name = 'gpt-5.6-sol'
join routing_group_models rgm on rgm.group_id = g.id and rgm.model_id = m.id
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
) u on true
where g.code = 'hzz_fl'
  and g.status = 'active'
  and exists (
      select 1
      from routing_group_supplier_credentials credential
      where credential.group_id = g.id
        and credential.supplier_id = s.id
        and credential.status = 'active'
        and credential.encrypted_credential is not null
  );
"@

    $parts = $configuration.Split('|')
    if ($parts.Count -ne 6) {
        throw "Red Spider production route is incomplete; expected one group/supplier/model/user tuple"
    }

    $groupId, $supplierId, $modelId, $originalSupplierStatus, $originalModelStatus, $userId = $parts

    $hmacKeyBase64 = (& ssh -i $SshKey "root@$Server" "docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1").Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($hmacKeyBase64)) {
        throw "Unable to load the API key HMAC configuration from the production app"
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
begin;
update routing_group_suppliers
set status = 'active', updated_at = now(), version = version + 1
where group_id = '$groupId'::uuid and supplier_id = '$supplierId'::uuid;

update routing_group_models
set source_status = 'active', updated_at = now(), version = version + 1
where group_id = '$groupId'::uuid and model_id = '$modelId'::uuid;

insert into api_keys (
    id, user_id, name, key_prefix, key_suffix, key_hash, key_hash_version,
    status, default_group_id, service_group_id, allowed_model_ids,
    allowed_group_ids, ip_allowlist, rpm_limit, tpm_limit,
    concurrency_limit, credit_limit, expires_at
) values (
    '$testKeyId'::uuid, '$userId'::uuid, 'Codex 红蜘蛛生产冒烟测试',
    '$keyPrefix', '$keySuffix', decode('$keyHashHex', 'hex'), 1,
    'active', '$groupId'::uuid, '$groupId'::uuid,
    '["$modelId"]'::jsonb, '["$groupId"]'::jsonb, '[]'::jsonb,
    5, 10000, 1, 1, now() + interval '15 minutes'
);
commit;
"@ | Out-Null
    $testConfigured = $true

    Add-TestLog "TEST request_id=$requestId endpoint=$Endpoint model=gpt-5.6-sol"
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

    $responseBody = $response.Content | ConvertFrom-Json
    $answer = [string]$responseBody.choices[0].message.content
    if ([string]::IsNullOrWhiteSpace($answer)) {
        throw "The response did not contain choices[0].message.content"
    }

    $routeLog = ""
    for ($attempt = 0; $attempt -lt 15 -and [string]::IsNullOrWhiteSpace($routeLog); $attempt++) {
        Start-Sleep -Seconds 1
        $routeLog = Invoke-ProductionSql @"
select concat_ws('|',
    request_log.status_code,
    supplier.code,
    channel.name,
    attempt.outcome,
    coalesce(attempt.upstream_status::text, ''),
    request_log.public_model
)
from request_logs request_log
join suppliers supplier on supplier.id = request_log.supplier_id
join channels channel on channel.id = request_log.channel_id
left join upstream_attempt_logs attempt on attempt.request_id = request_log.request_id
where request_log.request_id = '$requestId'
order by attempt.attempt_no
limit 1;
"@
    }

    $routeParts = $routeLog.Split('|')
    if ($routeParts.Count -ne 6) {
        throw "The production request log was not persisted in time"
    }
    if ($routeParts[0] -ne '200' -or $routeParts[1] -ne 'hzz' -or $routeParts[3] -ne 'success') {
        throw "Route validation failed: $routeLog"
    }

    Add-TestLog "ROUTE status=$($routeParts[0]) supplier=$($routeParts[1]) channel=$($routeParts[2]) outcome=$($routeParts[3]) upstream_status=$($routeParts[4]) model=$($routeParts[5])"
    Add-TestLog "PASS answer=$answer"
}
catch {
    $exitCode = 1
    Add-TestLog "FAIL $($_.Exception.Message)"
}
finally {
    if ($testConfigured) {
        try {
            Invoke-ProductionSql @"
begin;
update api_keys
set status = 'revoked', revoked_at = now(), status_changed_at = now(), version = version + 1
where id = '$testKeyId'::uuid;

update routing_group_suppliers
set status = '$originalSupplierStatus', updated_at = now(), version = version + 1
where group_id = '$groupId'::uuid and supplier_id = '$supplierId'::uuid;

update routing_group_models
set source_status = '$originalModelStatus', updated_at = now(), version = version + 1
where group_id = '$groupId'::uuid and model_id = '$modelId'::uuid;
commit;
"@ | Out-Null
            Add-TestLog "CLEANUP temporary API key revoked and original route statuses restored"
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

$logPath = Join-Path $logDir "hzz-production-$($requestId).log"
$logLines | Set-Content -Encoding utf8NoBOM $logPath
$logLines | ForEach-Object { Write-Output $_ }

if ($exitCode -ne 0) {
    throw "Red Spider production smoke test failed. See $logPath"
}

Write-Output "Log: $logPath"
