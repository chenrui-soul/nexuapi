param(
    [string]$LogName = "responses-stream-resilience",
    [string]$TestSelector = "OpenAiGatewayIntegrationTest#responsesSingleRouteDisconnectRetriesBeforeCommit+channelConcurrencyQueuesSecondTextRequestAndReleasesReservations,ChannelHealthIntegrationTest#successfulProbeDoesNotEraseRealGatewayFailure"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot "backend"
$referencePath = Join-Path $projectRoot "references\responses-stream-resilience-cases.json"
$logDir = Join-Path $PSScriptRoot "log"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$cases = (Get-Content -Raw -LiteralPath $referencePath | ConvertFrom-Json).cases
if ($cases.Count -ne 3) { throw "Ground truth case count must be 3" }
if ($cases[0].expected_attempt_outcomes -join ',' -ne 'supplier_failure,supplier_failure,supplier_failure,success') {
    throw "Single-route retry ground truth is invalid"
}
if ($cases[1].expected_channel_status -ne 'degraded') {
    throw "Health-state ground truth is invalid"
}
if ($cases[2].configured_concurrency -ne 1 -or $cases[2].expected_successes -ne 2) {
    throw "Text channel queue ground truth is invalid"
}

$logPath = Join-Path $logDir "$LogName-$((Get-Date).ToString('yyyyMMdd-HHmmss')).log"
Push-Location $backendRoot
try {
    docker compose -f compose.test.yaml up -d --wait
    if ($LASTEXITCODE -ne 0) { throw "Test dependencies failed to start" }
    $dockerArguments = @(
        "run", "--rm", "--network", "nexus-auth-test_default",
        "-e", "DB_URL=jdbc:postgresql://postgres:5432/nexus_api_test",
        "-e", "DB_USERNAME=nexus", "-e", "DB_PASSWORD=nexus-test-password",
        "-e", "REDIS_HOST=redis", "-e", "REDIS_PORT=6379", "-e", "REDIS_PASSWORD=nexus-test-password",
        "-e", "NEXUS_FIELD_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "-e", "NEXUS_LOOKUP_HMAC_KEY=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "-e", "NEXUS_API_KEY_HMAC_KEY_V1=NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ=",
        "-e", "SPRING_PROFILES_ACTIVE=test",
        "-v", "${backendRoot}:/workspace", "-v", "nexus_maven_repository:/root/.m2",
        "-w", "/workspace", "maven:3.9-eclipse-temurin-21-alpine",
        "mvn", "-B", "-Dtest=$TestSelector", "test"
    )
    $ErrorActionPreference = "Continue"
    & docker @dockerArguments 2>&1 |
        Tee-Object -FilePath $logPath
    $testExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($testExitCode -ne 0) { exit $testExitCode }
} finally {
    $ErrorActionPreference = "Continue"
    docker compose -f compose.test.yaml down -v | Out-Null
    $ErrorActionPreference = "Stop"
    Pop-Location
}
