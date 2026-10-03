[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$DbContainer = 'nexus-api-postgres-1',
    [string]$AppContainer = 'nexus-api-app-1',
    [string]$DbUser = 'nexus',
    [string]$DbName = 'nexus_api',
    [string]$MockPort = '18080',
    [string]$Levels = '10,25,50,100',
    [int]$DurationSeconds = 30,
    [switch]$FailFast
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $PSScriptRoot 'log'
$fixturePath = Join-Path $root 'references/pressure-load-cases.json'
$k6 = 'C:\Program Files\k6\k6.exe'
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportPath = Join-Path $logDir "pressure-production-k6-$runStamp.json"
$runPrefix = "loadtest-$runStamp"
$mockContainer = 'nexus-loadmock'
$apiKeyId = $null
$apiSecret = $null
$userId = $null
$groupId = '27000d13-e4b9-477d-b8cd-605e982a4246'
$chatChannelId = 'ae9611f7-bad7-4162-8074-475c5656c303'
$responsesChannelId = '231bfac0-3bd9-49f5-bdbd-e6b06e06f533'
$originalChatUrl = $null
$originalResponsesUrl = $null
$originalChatStatus = $null
$originalResponsesStatus = $null
$walletBefore = $null
$levelReports = [System.Collections.Generic.List[object]]::new()
$levelValues = @($Levels -split '[,; ]+' | Where-Object { $_ } | ForEach-Object { [int]$_ })

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
if (-not (Test-Path -LiteralPath $k6)) { throw "k6 not found at $k6" }
if (-not (Test-Path -LiteralPath $fixturePath)) { throw "Fixture not found: $fixturePath" }

function Invoke-Psql([string]$Sql) {
    $output = $Sql | & docker exec -i $DbContainer psql -X -q -v ON_ERROR_STOP=1 -U $DbUser -d $DbName -At -P pager=off
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL command failed' }
    return ($output -join "`n").Trim()
}

function ConvertTo-Hex([byte[]]$Bytes) {
    return -join ($Bytes | ForEach-Object { $_.ToString('x2') })
}

function Get-AppEnv([string]$Name) {
    $inspect = docker inspect $AppContainer | ConvertFrom-Json -Depth 20
    $entry = $inspect[0].Config.Env | Where-Object { $_ -like "$Name=*" } | Select-Object -First 1
    if (-not $entry) { throw "Missing $Name in $AppContainer" }
    return $entry.Substring($entry.IndexOf('=') + 1)
}

function Get-Stats {
    $raw = docker stats --no-stream --format '{{json .}}' $AppContainer
    return ($raw | ConvertFrom-Json)
}

function Get-DbSnapshot([string]$Prefix) {
    $sql = @"
SELECT json_build_object(
  'requests', (SELECT count(*) FROM request_logs WHERE request_id LIKE '$Prefix-%'),
  'billing_details', (SELECT count(*) FROM request_billing_details WHERE request_id LIKE '$Prefix-%'),
  'upstream_attempts', (SELECT count(*) FROM upstream_attempt_logs WHERE request_id LIKE '$Prefix-%'),
  'reservations_total', (SELECT count(*) FROM wallet_reservations WHERE request_id LIKE '$Prefix-%'),
  'reservations_reserved', (SELECT count(*) FROM wallet_reservations WHERE request_id LIKE '$Prefix-%' AND status='reserved'),
  'reservations_settled', (SELECT count(*) FROM wallet_reservations WHERE request_id LIKE '$Prefix-%' AND status='settled'),
  'reservations_released', (SELECT count(*) FROM wallet_reservations WHERE request_id LIKE '$Prefix-%' AND status='released'),
  'wallet', (SELECT row_to_json(w) FROM (SELECT permanent_credits, expiring_credits, frozen_credits, version FROM wallet_accounts WHERE user_id='$userId') w)
);
"@
    return (Invoke-Psql $sql | ConvertFrom-Json -Depth 20)
}

try {
    $fixture = Get-Content -Raw -LiteralPath $fixturePath | ConvertFrom-Json -Depth 20
    # Create an isolated test identity. Its password/email are never used for
    # login; copying the existing bcrypt hash satisfies the schema without
    # introducing any real credential. All billing/audit rows stay attached to
    # this disposable identity for post-test inspection.
    $userId = [guid]::NewGuid().ToString()
    $sourcePasswordHash = [string](Invoke-Psql "SELECT password_hash FROM users ORDER BY created_at LIMIT 1")
    $emailCipher = [byte[]]::new(32); [Security.Cryptography.RandomNumberGenerator]::Fill($emailCipher)
    $emailLookup = [byte[]]::new(32); [Security.Cryptography.RandomNumberGenerator]::Fill($emailLookup)
    $testEmailCipher = ConvertTo-Hex $emailCipher
    $testEmailLookup = ConvertTo-Hex $emailLookup
    $userSql = @"
INSERT INTO users (id,display_name,email_ciphertext,email_lookup_hash,password_hash,status)
VALUES ('$userId','k6-load-$runStamp',decode('$testEmailCipher','hex'),decode('$testEmailLookup','hex'),'$sourcePasswordHash','active');
INSERT INTO wallet_accounts (user_id,permanent_credits,expiring_credits,frozen_credits)
VALUES ('$userId',100000,0,0);
"@
    Invoke-Psql $userSql | Out-Null

    $walletBefore = Get-DbSnapshot $runPrefix
    $routeRows = Invoke-Psql "SELECT id || '|' || base_url || '|' || status FROM channels WHERE id IN ('$chatChannelId','$responsesChannelId') ORDER BY id"
    foreach ($row in ($routeRows -split "`n")) {
        if ($row -match '^' + [regex]::Escape($chatChannelId) + '\|([^|]+)\|([^|]+)$') { $originalChatUrl = $Matches[1]; $originalChatStatus = $Matches[2] }
        if ($row -match '^' + [regex]::Escape($responsesChannelId) + '\|([^|]+)\|([^|]+)$') { $originalResponsesUrl = $Matches[1]; $originalResponsesStatus = $Matches[2] }
    }
    if (-not $originalChatUrl -or -not $originalResponsesUrl) { throw 'Target channels not found' }

    $hmacKey = [Convert]::FromBase64String((Get-AppEnv 'NEXUS_API_KEY_HMAC_KEY_V1'))
    $random = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $encoded = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+','-').Replace('/','_')
    $apiSecret = 'sk-nx-v1_' + $encoded
    $digestor = [Security.Cryptography.HMACSHA256]::new($hmacKey)
    try { $digest = $digestor.ComputeHash([Text.Encoding]::UTF8.GetBytes('api-key:v1:' + $apiSecret)) } finally { $digestor.Dispose(); [Array]::Clear($hmacKey,0,$hmacKey.Length) }
    $apiKeyId = [guid]::NewGuid().ToString()
    $hashHex = ConvertTo-Hex $digest
    $prefix = $apiSecret.Substring(0,16)
    $suffix = $apiSecret.Substring($apiSecret.Length - 8)
    $insertSql = @"
INSERT INTO api_keys (id,user_id,name,key_prefix,key_suffix,key_hash,key_hash_version,status,default_group_id,service_group_id,allowed_model_ids,allowed_group_ids,ip_allowlist,expires_at)
VALUES ('$apiKeyId','$userId','Production k6 load test $runStamp','$prefix','$suffix',decode('$hashHex','hex'),1,'active','$groupId','$groupId','[]'::jsonb,'["$groupId"]'::jsonb,'[]'::jsonb,now()+interval '2 hours');
"@
    Invoke-Psql $insertSql | Out-Null

    $routeSql = @"
UPDATE channels SET base_url='http://${mockContainer}:$MockPort/v1/chat/completions', status='active', updated_at=now(), version=version+1 WHERE id='$chatChannelId';
UPDATE channels SET base_url='http://${mockContainer}:$MockPort/v1/responses', status='active', updated_at=now(), version=version+1 WHERE id='$responsesChannelId';
"@
    Invoke-Psql $routeSql | Out-Null

    $mockScript = Join-Path $PSScriptRoot 'mock-upstream-load.py'
    docker rm -f $mockContainer 2>$null | Out-Null
    docker run -d --rm --name $mockContainer --network nexus-api_default -p "${MockPort}:${MockPort}" `
        -v "${mockScript}:/app/mock-upstream-load.py:ro" python:3.12-slim-bookworm `
        python /app/mock-upstream-load.py --port $MockPort | Out-Null
    Start-Sleep -Milliseconds 700
    $health = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$MockPort/health" -TimeoutSec 5
    if ($health.StatusCode -ne 200) { throw 'Mock upstream did not start' }

    # Warm-up proves that the modified database routes are reachable before load.
    $warmupBody = @{ model='gpt-5.6-sol'; messages=@(@{ role='user'; content='warmup' }); stream=$false } | ConvertTo-Json -Compress
    $warmup = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/v1/chat/completions" -Method Post -Headers @{ Authorization="Bearer $apiSecret"; 'X-Request-Id'="$runPrefix-warmup" } -ContentType 'application/json' -Body $warmupBody -SkipHttpErrorCheck -TimeoutSec 30
    if ($warmup.StatusCode -lt 200 -or $warmup.StatusCode -ge 300) { throw "Gateway warm-up failed with HTTP $($warmup.StatusCode): $($warmup.Content)" }

    foreach ($vus in $levelValues) {
        $runId = "$runPrefix-$vus"
        Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$MockPort/__reset" -Method Post | Out-Null
        $beforeStats = Get-Stats
        $summaryPath = Join-Path $logDir "k6-$runId-summary.json"
        $stdoutPath = Join-Path $logDir "k6-$runId.stdout.log"
        $stderrPath = Join-Path $logDir "k6-$runId.stderr.log"
        $env:BASE_URL = $BaseUrl
        $env:API_KEY = $apiSecret
        $env:RUN_ID = $runId
        $env:VUS = "$vus"
        $env:DURATION = "${DurationSeconds}s"
        $env:SUMMARY_PATH = $summaryPath
        $k6Output = & $k6 run --vus $vus --duration "${DurationSeconds}s" (Join-Path $PSScriptRoot 'k6-gateway-business.js') 1> $stdoutPath 2> $stderrPath
        $exitCode = $LASTEXITCODE
        $afterStats = Get-Stats
        $db = Get-DbSnapshot $runId
        $mock = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$MockPort/__stats" | Select-Object -ExpandProperty Content | ConvertFrom-Json -Depth 20)
        $levelReports.Add([ordered]@{
            vus = $vus
            duration_seconds = $DurationSeconds
            k6_exit_code = $exitCode
            summary_path = $summaryPath
            stdout_path = $stdoutPath
            stderr_path = $stderrPath
            database = $db
            mock_upstream = $mock
            container_before = $beforeStats
            container_after = $afterStats
        })
        if ($FailFast -and $exitCode -ne 0) { throw "k6 failed at VUS=$vus; see $stdoutPath and $stderrPath" }
        if ([int]$db.reservations_reserved -ne 0) { throw "Reserved wallet rows leaked at VUS=$vus" }
    }

    $final = [ordered]@{
        generated_at = [DateTimeOffset]::Now.ToString('o')
        target = $BaseUrl
        upstream = "local mock http://127.0.0.1:$MockPort"
        fixture = $fixturePath
        run_prefix = $runPrefix
        levels = $levelReports
        wallet_before = $walletBefore.wallet
        wallet_after_load = (Get-DbSnapshot $runPrefix).wallet
        status = 'passed'
    }
    $final | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    $final | ConvertTo-Json -Depth 30
    "REPORT_PATH=$reportPath"
}
finally {
    $ErrorActionPreference = 'Continue'
    if ($originalChatUrl -and $originalResponsesUrl) {
        $restore = @"
UPDATE channels SET base_url='$originalChatUrl', status='$originalChatStatus', updated_at=now(), version=version+1 WHERE id='$chatChannelId';
UPDATE channels SET base_url='$originalResponsesUrl', status='$originalResponsesStatus', updated_at=now(), version=version+1 WHERE id='$responsesChannelId';
"@
        try { Invoke-Psql $restore | Out-Null } catch { }
    }
    if ($apiKeyId) {
        try {
            $cleanup = @"
-- 账本和请求审计表为 append-only/保留审计证据；只撤销临时令牌并停用测试身份。
UPDATE api_keys SET status='revoked', revoked_at=now(), status_changed_at=now(), version=version+1 WHERE id='$apiKeyId';
UPDATE users SET status='deleted', deleted_at=now(), updated_at=now(), version=version+1 WHERE id='$userId';
"@
            Invoke-Psql $cleanup | Out-Null
        } catch { }
    }
    elseif ($userId) {
        try { Invoke-Psql "UPDATE users SET status='deleted', deleted_at=now(), updated_at=now(), version=version+1 WHERE id='$userId';" | Out-Null } catch { }
    }
    docker rm -f $mockContainer 2>$null | Out-Null
    Remove-Item Env:BASE_URL,Env:API_KEY,Env:RUN_ID,Env:VUS,Env:DURATION,Env:SUMMARY_PATH -ErrorAction SilentlyContinue
    $ErrorActionPreference = 'Stop'
}
