[CmdletBinding()]
param(
    [string]$FixturePath,
    [ValidatePattern('^[a-z0-9-]+$')]
    [string]$ValidationTag = 'v43'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($FixturePath)) {
    $FixturePath = Join-Path $root 'references/video-create-v43-validation.json'
}
$fixturePath = [System.IO.Path]::GetFullPath($FixturePath)
$logDirectory = Join-Path $PSScriptRoot 'log'
$logPath = Join-Path $logDirectory ('video-create-' + $ValidationTag + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
$fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json -Depth 30
$dbContainer = [string]$fixture.environment.db_container
$appContainer = [string]$fixture.environment.app_container
$userId = [string]$fixture.environment.user_id
$groupId = [string]$fixture.environment.group_id
$baseUrl = [string]$fixture.environment.base_url
$model = [string]$fixture.request.model
$requestId = 'video-' + $ValidationTag + '-' + [guid]::NewGuid().ToString('N')
$keyId = $null
$secret = $null
$httpStatus = 0
$taskId = $null
$safeError = $null
$responseKeys = @()
$clientElapsedMs = 0L
$fatalError = $null

New-Item -ItemType Directory -Force $logDirectory | Out-Null

function Invoke-Psql {
    param([Parameter(Mandatory)][string]$Sql)
    $output = $Sql | & docker exec -i $dbContainer psql -X -q -v ON_ERROR_STOP=1 `
        -U nexus -d nexus_api -At -P pager=off
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL command failed' }
    return ($output -join "`n").Trim()
}

function ConvertTo-Hex {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    return -join ($Bytes | ForEach-Object { $_.ToString('x2') })
}

function New-TemporaryApiKey {
    $containerJson = & docker inspect $appContainer | ConvertFrom-Json -Depth 20
    $hmacEntry = $containerJson[0].Config.Env |
        Where-Object { $_ -like 'NEXUS_API_KEY_HMAC_KEY_V1=*' } |
        Select-Object -First 1
    if (-not $hmacEntry) { throw 'App container does not expose API key HMAC v1 configuration' }
    $hmacKey = [Convert]::FromBase64String($hmacEntry.Substring($hmacEntry.IndexOf('=') + 1))
    if ($hmacKey.Length -ne 32) { throw 'API key HMAC v1 must decode to 32 bytes' }

    $random = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $encoded = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $localSecret = 'sk-nx-v1_' + $encoded
    $message = [Text.Encoding]::UTF8.GetBytes('api-key:v1:' + $localSecret)
    $hasher = [Security.Cryptography.HMACSHA256]::new($hmacKey)
    try { $digest = $hasher.ComputeHash($message) } finally { $hasher.Dispose() }
    [Array]::Clear($hmacKey, 0, $hmacKey.Length)

    $script:keyId = [guid]::NewGuid().ToString()
    $prefix = $localSecret.Substring(0, 16)
    $suffix = $localSecret.Substring($localSecret.Length - 8)
    $hashHex = ConvertTo-Hex $digest
    $sql = @"
INSERT INTO api_keys (
    id, user_id, name, key_prefix, key_suffix, key_hash, key_hash_version, status,
    default_group_id, service_group_id, allowed_model_ids, allowed_group_ids, ip_allowlist,
    expires_at
) VALUES (
    '$keyId', '$userId', 'Codex $ValidationTag video validation', '$prefix', '$suffix',
    decode('$hashHex','hex'), 1, 'active', '$groupId', '$groupId', '[]'::jsonb,
    '[`"$groupId`"]'::jsonb, '[]'::jsonb, now() + interval '30 minutes'
);
"@
    Invoke-Psql $sql | Out-Null
    return $localSecret
}

function Find-TaskId {
    param([object]$Node)
    if ($null -eq $Node) { return $null }
    foreach ($name in @('task_id', 'id', 'taskId')) {
        $property = $Node.PSObject.Properties[$name]
        if ($property -and $property.Value -is [string] -and $property.Value) {
            return [string]$property.Value
        }
    }
    foreach ($name in @('data', 'result', 'task')) {
        $property = $Node.PSObject.Properties[$name]
        if ($property) {
            $nested = Find-TaskId $property.Value
            if ($nested) { return $nested }
        }
    }
    return $null
}

function Get-Wallet {
    $json = Invoke-Psql @"
SELECT row_to_json(w)::text FROM (
    SELECT permanent_credits::text AS permanent,
           expiring_credits::text AS expiring,
           frozen_credits::text AS frozen,
           (permanent_credits + expiring_credits - frozen_credits)::text AS available
      FROM wallet_accounts
     WHERE user_id='$userId'
) w;
"@
    return $json | ConvertFrom-Json
}

function Get-Evidence {
    $sql = @"
SELECT row_to_json(e)::text FROM (
    SELECT
        (SELECT row_to_json(r) FROM (
            SELECT request_id, status_code, platform_error_code, duration_ms,
                   billed_amount::text AS billed_amount, public_model, upstream_model,
                   retry_count, upstream_error_summary, supplier_error_category
              FROM request_logs WHERE request_id='$requestId' LIMIT 1
        ) r) AS request_log,
        (SELECT row_to_json(b) FROM (
            SELECT engine_mode, billing_type, base_unit_price::text AS base_unit_price,
                   effective_unit_price::text AS effective_unit_price,
                   group_multiplier::text AS group_multiplier, usage_snapshot,
                   calculated_amount::text AS calculated_amount,
                   settled_amount::text AS settled_amount, status
              FROM request_billing_details WHERE request_id='$requestId' LIMIT 1
        ) b) AS billing,
        (SELECT coalesce(json_agg(row_to_json(a) ORDER BY a.attempt_no), '[]'::json) FROM (
            SELECT attempt_no, duration_ms, outcome, upstream_status,
                   error_category, error_summary
              FROM upstream_attempt_logs WHERE request_id='$requestId'
        ) a) AS attempts,
        (SELECT row_to_json(v) FROM (
            SELECT status, reserved_amount::text AS reserved_amount,
                   settled_amount::text AS settled_amount,
                   refunded_amount::text AS refunded_amount
              FROM wallet_reservations WHERE request_id='$requestId' LIMIT 1
        ) v) AS reservation
) e;
"@
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $json = Invoke-Psql $sql
        if ($json) {
            $evidence = $json | ConvertFrom-Json -Depth 40
            if ($evidence.request_log) { return $evidence }
        }
        Start-Sleep -Milliseconds 250
    }
    return $null
}

$preflightJson = Invoke-Psql @"
SELECT row_to_json(p)::text FROM (
    SELECT m.id AS model_id, m.public_name, m.billing_type,
           m.unit_price::text AS unit_price, m.active_pricing_version_id,
           g.name AS group_name, g.price_multiplier::text AS group_multiplier,
           (SELECT count(*) FROM model_interfaces mi WHERE mi.model_id=m.id) AS document_relation_count,
           (SELECT string_agg(i.interface_code || ':' || i.http_method, ',' ORDER BY i.interface_code)
              FROM model_interfaces mi JOIN api_interfaces i ON i.id=mi.interface_id
             WHERE mi.model_id=m.id) AS document_relations,
           (SELECT count(*) FROM routing_group_supplier_credentials c
             WHERE c.group_id=g.id AND c.status='active' AND c.encrypted_credential IS NOT NULL) AS active_credentials,
           (SELECT count(*) FROM routing_group_suppliers r JOIN channels c ON c.supplier_id=r.supplier_id
             WHERE r.group_id=g.id AND r.status='active' AND c.endpoint_type='video'
               AND c.request_method='POST' AND c.status IN ('active','degraded')
               AND lower(rtrim(c.base_url,'/')) LIKE '%/videos') AS post_video_channels
      FROM ai_models m JOIN routing_group_models gm ON gm.model_id=m.id
      JOIN routing_groups g ON g.id=gm.group_id
     WHERE m.public_name='$model' AND g.id='$groupId' AND m.status='active'
       AND gm.source_status='active' AND g.status='active'
     LIMIT 1
) p;
"@
$preflight = $preflightJson | ConvertFrom-Json
$walletBefore = Get-Wallet

try {
    $secret = New-TemporaryApiKey
    $headers = @{ Authorization = 'Bearer ' + $secret; 'X-Request-Id' = $requestId }
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-WebRequest -Uri ($baseUrl + [string]$fixture.request.path) -Method Post `
        -Headers $headers -ContentType 'application/json' `
        -Body ($fixture.request.body | ConvertTo-Json -Depth 20 -Compress) `
        -SkipHttpErrorCheck -TimeoutSec 300
    $stopwatch.Stop()
    $clientElapsedMs = $stopwatch.ElapsedMilliseconds
    $httpStatus = [int]$response.StatusCode
    if ($response.Content) {
        try {
            $json = $response.Content | ConvertFrom-Json -Depth 40
            $responseKeys = @($json.PSObject.Properties.Name)
            $taskId = Find-TaskId $json
            if ($httpStatus -lt 200 -or $httpStatus -ge 300) {
                $safeError = [ordered]@{
                    code = [string]($json.error.code ?? $json.code ?? $json.error.type)
                    message = [string]($json.error.message ?? $json.message)
                }
            }
        } catch {
            $safeError = [ordered]@{ code = 'NON_JSON_RESPONSE'; message = '响应不是合法 JSON' }
        }
    }
} catch {
    $fatalError = $_.Exception.Message
} finally {
    $secret = $null
    if ($keyId) {
        try {
            Invoke-Psql "UPDATE api_keys SET status='revoked', revoked_at=now(), status_changed_at=now(), version=version+1 WHERE id='$keyId' AND status <> 'revoked';" | Out-Null
        } catch {
            if (-not $fatalError) { $fatalError = '短期 API 令牌撤销失败' }
        }
    }
}

$evidence = Get-Evidence
$walletAfter = Get-Wallet
$durationSeconds = [decimal]$fixture.request.body.duration
$unitPrice = [decimal]::Parse([string]$preflight.unit_price, [Globalization.CultureInfo]::InvariantCulture)
$groupMultiplier = [decimal]::Parse([string]$preflight.group_multiplier, [Globalization.CultureInfo]::InvariantCulture)
$expectedAmount = $unitPrice * $durationSeconds * $groupMultiplier
$beforeAvailable = [decimal]::Parse([string]$walletBefore.available, [Globalization.CultureInfo]::InvariantCulture)
$afterAvailable = [decimal]::Parse([string]$walletAfter.available, [Globalization.CultureInfo]::InvariantCulture)
$walletDelta = $beforeAvailable - $afterAvailable
$billedAmount = if ($evidence -and $evidence.request_log) {
    [decimal]::Parse([string]$evidence.request_log.billed_amount, [Globalization.CultureInfo]::InvariantCulture)
} else { [decimal]0 }
$settledAmount = if ($evidence -and $evidence.billing) {
    [decimal]::Parse([string]$evidence.billing.settled_amount, [Globalization.CultureInfo]::InvariantCulture)
} else { [decimal]0 }
$tokenStatus = if ($keyId) { Invoke-Psql "SELECT status FROM api_keys WHERE id='$keyId';" } else { 'not_created' }
$contractPassed = $httpStatus -ge 200 -and $httpStatus -lt 300 -and -not [string]::IsNullOrWhiteSpace($taskId)
$requestLogPassed = $evidence -and $evidence.request_log -and [int]$evidence.request_log.status_code -ge 200 -and [int]$evidence.request_log.status_code -lt 300
$attemptPassed = $evidence -and @($evidence.attempts | Where-Object outcome -eq 'success').Count -gt 0
$billingPassed = $contractPassed -and $billedAmount -eq $expectedAmount -and $settledAmount -eq $expectedAmount -and $walletDelta -eq $expectedAmount -and $evidence.billing.status -eq 'settled'
$cleanupPassed = $tokenStatus -eq 'revoked'

$summary = [ordered]@{
    generated_at = (Get-Date).ToString('o')
    request_id = $requestId
    method = [string]$fixture.request.method
    path = [string]$fixture.request.path
    model = $model
    group_id = $groupId
    preflight = $preflight
    request = [ordered]@{
        duration_seconds = [int]$fixture.request.body.duration
        resolution = [string]$fixture.request.body.resolution
        aspect_ratio = [string]$fixture.request.body.aspect_ratio
        generate_audio = [bool]$fixture.request.body.generate_audio
    }
    response = [ordered]@{
        http_status = $httpStatus
        client_elapsed_ms = $clientElapsedMs
        task_id = $taskId
        top_level_keys = $responseKeys
        error = $safeError
        fatal_error = $fatalError
    }
    expected = [ordered]@{
        billing_formula = 'unit_price × duration_seconds × group_multiplier'
        expected_amount = $expectedAmount.ToString('0.000000000000', [Globalization.CultureInfo]::InvariantCulture)
    }
    actual = [ordered]@{
        billed_amount = $billedAmount.ToString('0.000000000000', [Globalization.CultureInfo]::InvariantCulture)
        settled_amount = $settledAmount.ToString('0.000000000000', [Globalization.CultureInfo]::InvariantCulture)
        wallet_delta = $walletDelta.ToString('0.000000000000', [Globalization.CultureInfo]::InvariantCulture)
        wallet_before_available = [string]$walletBefore.available
        wallet_after_available = [string]$walletAfter.available
    }
    evidence = $evidence
    assertions = [ordered]@{
        contract_passed = [bool]$contractPassed
        request_log_passed = [bool]$requestLogPassed
        upstream_attempt_passed = [bool]$attemptPassed
        billing_passed = [bool]$billingPassed
        temporary_key_revoked = [bool]$cleanupPassed
        passed = [bool]($contractPassed -and $requestLogPassed -and $attemptPassed -and $billingPassed -and $cleanupPassed)
    }
}

$jsonOutput = $summary | ConvertTo-Json -Depth 40
[IO.File]::WriteAllText($logPath, $jsonOutput + "`r`n", [Text.UTF8Encoding]::new($false))
$summary | ConvertTo-Json -Depth 12
Write-Output ('LOG_PATH=' + $logPath)
if (-not $summary.assertions.passed) { exit 1 }
