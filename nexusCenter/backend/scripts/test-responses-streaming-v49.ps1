param(
    [string]$MavenImage = "maven:3.9-eclipse-temurin-21-alpine"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $PSScriptRoot "log"
$logPath = Join-Path $logDirectory "responses-streaming-v49-tests.log"
$stdoutPath = Join-Path $logDirectory "responses-streaming-v49-tests.stdout.tmp"
$stderrPath = Join-Path $logDirectory "responses-streaming-v49-tests.stderr.tmp"
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$testCommand = "mvn -B -Dtest=OpenAiGatewayIntegrationTest#responsesUsesConfiguredUpstreamPathAndCompletesBillingLog+responsesStreamingPassesOfficialEventsAndSettlesUsage+responsesStreamsCreatedEventBeforeUpstreamCompletion+responsesCompletedEventWinsOverPrematureTransportClose+responsesFirstFailureEventRetriesBeforeCommit+responsesFailureAfterCreatedEventDoesNotReplayOnAnotherRoute+streamingCallPassesSseDoneAndSettlesUsage clean test"

Push-Location $projectRoot
try {
    $ErrorActionPreference = "Continue"
    docker compose -f compose.test.yaml up -d --wait
    if ($LASTEXITCODE -ne 0) { throw "Responses streaming test dependencies failed to start" }

    $arguments = @(
        "run", "--rm",
        "--network", "nexus-auth-test_default",
        "-e", "DB_URL=jdbc:postgresql://postgres:5432/nexus_api_test",
        "-e", "DB_USERNAME=nexus",
        "-e", "DB_PASSWORD=nexus-test-password",
        "-e", "REDIS_HOST=redis",
        "-e", "REDIS_PORT=6379",
        "-e", "REDIS_PASSWORD=nexus-test-password",
        "-e", "NEXUS_FIELD_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "-e", "NEXUS_LOOKUP_HMAC_KEY=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "-e", "NEXUS_API_KEY_HMAC_KEY_V1=NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ=",
        "-e", "SPRING_PROFILES_ACTIVE=test",
        "-v", ('"' + $projectRoot + ':/workspace"'),
        "-v", "nexus_maven_repository:/root/.m2",
        "-w", "/workspace",
        $MavenImage,
        "sh", "-lc", ('"' + $testCommand + '"')
    )
    $process = Start-Process -FilePath "docker" -ArgumentList $arguments -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $stdout = if (Test-Path $stdoutPath) { [IO.File]::ReadAllText($stdoutPath) } else { "" }
    $stderr = if (Test-Path $stderrPath) { [IO.File]::ReadAllText($stderrPath) } else { "" }
    [IO.File]::WriteAllText($logPath, $stdout + $stderr, [Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    if ($process.ExitCode -ne 0) {
        throw "Responses streaming tests failed with exit code $($process.ExitCode)"
    }
    Write-Output "Responses streaming tests passed. Log: $logPath"
} finally {
    $ErrorActionPreference = "Continue"
    docker compose -f compose.test.yaml down -v
    $ErrorActionPreference = "Stop"
    Pop-Location
}
