param(
    [string]$MavenImage = "maven:3.9.11-eclipse-temurin-21"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $PSScriptRoot "log"
$testLog = Join-Path $logDirectory "channel-health-config-version-tests.log"
$groundTruthPath = Join-Path $projectRoot "references\channel-health-config-version-regression.json"

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$groundTruth = Get-Content -Raw $groundTruthPath | ConvertFrom-Json
if ($groundTruth.groundTruth.scheduledProbeFailure.configurationVersionDelta -ne 0) {
    throw "Invalid ground truth: health writes must not change the configuration version"
}

Push-Location $projectRoot
try {
    docker compose -f compose.test.yaml up -d --wait

    $workspaceVolume = '"' + "${projectRoot}:/workspace" + '"'
    $dockerArguments = @(
        'run', '--rm',
        '--network', 'nexus-auth-test_default',
        '-e', 'DB_URL=jdbc:postgresql://postgres:5432/nexus_api_test',
        '-e', 'DB_USERNAME=nexus',
        '-e', 'DB_PASSWORD=nexus-test-password',
        '-e', 'REDIS_HOST=redis',
        '-e', 'REDIS_PORT=6379',
        '-e', 'REDIS_PASSWORD=nexus-test-password',
        '-e', 'NEXUS_FIELD_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=',
        '-e', 'NEXUS_LOOKUP_HMAC_KEY=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=',
        '-e', 'NEXUS_API_KEY_HMAC_KEY_V1=NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ=',
        '-e', 'SPRING_PROFILES_ACTIVE=test',
        '-v', $workspaceVolume,
        '-v', 'nexus_maven_repository:/root/.m2',
        '-w', '/workspace',
        $MavenImage,
        'mvn', '-B', '-Dtest=ChannelHealthIntegrationTest', 'test'
    )

    $stdoutPath = Join-Path $logDirectory "channel-health-config-version.stdout.tmp"
    $stderrPath = Join-Path $logDirectory "channel-health-config-version.stderr.tmp"
    $testProcess = Start-Process -FilePath "docker" -ArgumentList $dockerArguments `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $stdout = if (Test-Path $stdoutPath) { [System.IO.File]::ReadAllText($stdoutPath) } else { "" }
    $stderr = if (Test-Path $stderrPath) { [System.IO.File]::ReadAllText($stderrPath) } else { "" }
    $combinedOutput = $stdout + $stderr
    [System.IO.File]::WriteAllText($testLog, $combinedOutput, [System.Text.UTF8Encoding]::new($false))
    Write-Output $combinedOutput
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

    if ($testProcess.ExitCode -ne 0) {
        throw "Channel health configuration-version regression failed with exit code $($testProcess.ExitCode)"
    }
    Write-Output "Channel health configuration-version regression log: $testLog"
} finally {
    docker compose -f compose.test.yaml down -v
    Pop-Location
}
