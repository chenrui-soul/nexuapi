param([string]$MavenImage = "maven:3.9-eclipse-temurin-21-alpine")

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path $projectRoot "references/stream-edge-cases-v60.json"
$logDirectory = Join-Path $PSScriptRoot "log"
$logPath = Join-Path $logDirectory "stream-edge-cases-v60-tests.log"
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$cases = Get-Content -Raw $fixture | ConvertFrom-Json
if ($cases.cases.Count -ne 5) { throw "Synthetic stream fixture is incomplete" }
foreach ($case in $cases.cases) {
    if (-not $case.events -or -not $case.expected) { throw "Invalid synthetic case: $($case.id)" }
}

$testCommand = "mvn -B -Dtest=OpenAiGatewayIntegrationTest#responsesStreamingPassesOfficialEventsAndSettlesUsage+responsesStreamsCreatedEventBeforeUpstreamCompletion+responsesCompletedEventWinsOverPrematureTransportClose+responsesFirstFailureEventRetriesBeforeCommit+responsesFailureAfterCreatedEventDoesNotReplayOnAnotherRoute+streamFailureAfterFirstEventDoesNotSwitchRoute clean test"
Push-Location $projectRoot
try {
    docker compose -f compose.test.yaml up -d --wait
    if ($LASTEXITCODE -ne 0) { throw "Test dependencies failed to start" }
    $arguments = @(
        "run", "--rm", "--network", "nexus-auth-test_default",
        "-e", "DB_URL=jdbc:postgresql://postgres:5432/nexus_api_test",
        "-e", "DB_USERNAME=nexus", "-e", "DB_PASSWORD=nexus-test-password",
        "-e", "REDIS_HOST=redis", "-e", "REDIS_PORT=6379", "-e", "REDIS_PASSWORD=nexus-test-password",
        "-e", "NEXUS_FIELD_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "-e", "NEXUS_LOOKUP_HMAC_KEY=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "-e", "NEXUS_API_KEY_HMAC_KEY_V1=NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ=",
        "-e", "SPRING_PROFILES_ACTIVE=test", "-v", "${projectRoot}:/workspace",
        "-v", "nexus_maven_repository:/root/.m2", "-w", "/workspace", $MavenImage, "sh", "-lc", $testCommand
    )
    $ErrorActionPreference = "Continue"
    $output = & docker @arguments 2>&1
    $dockerExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $output | Set-Content -LiteralPath $logPath -Encoding UTF8
    if ($dockerExitCode -ne 0) { throw "Synthetic stream edge-case tests failed (exit $dockerExitCode)" }
    Add-Content -LiteralPath $logPath -Value "Synthetic cases validated: $($cases.cases.id -join ', ')"
    Write-Output "Synthetic stream edge-case tests passed. Log: $logPath"
} finally {
    docker compose -f compose.test.yaml down -v | Out-Null
    Pop-Location
}
