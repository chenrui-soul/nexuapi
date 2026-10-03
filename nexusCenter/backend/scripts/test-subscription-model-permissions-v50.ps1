param(
    [switch]$Full,
    [string]$JdkImage = "docker.1ms.run/library/eclipse-temurin:21-jdk-alpine"
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $PSScriptRoot 'log'
$logFile = Join-Path $logDirectory $(if ($Full) {
    'subscription-model-permissions-v50-full-test.log'
} else {
    'subscription-model-permissions-v50-test.log'
})

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
Push-Location $projectRoot
try {
    $ErrorActionPreference = 'Continue'
    docker compose -f compose.test.yaml up -d --wait
    if ($LASTEXITCODE -ne 0) { throw '隔离测试依赖启动失败' }

    $tests = 'SubscriptionBillingIntegrationTest,ApiKeyIntegrationTest,ModelMarketIntegrationTest,SchemaDocumentationIntegrationTest'
    $testCommand = "apk add --no-cache maven >/tmp/apk.log && mvn -B -Dtest=$tests test"
    if ($Full) {
        $testCommand = 'apk add --no-cache maven >/tmp/apk.log && mvn -B test'
    }
    if ($JdkImage -match '(^|/)maven:') {
        $testCommand = if ($Full) { 'mvn -B test' } else { "mvn -B -Dtest=$tests test" }
    }

    $stdoutPath = Join-Path $logDirectory 'subscription-model-permissions-v50.stdout.tmp'
    $stderrPath = Join-Path $logDirectory 'subscription-model-permissions-v50.stderr.tmp'
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
        $JdkImage,
        'sh', '-lc', ('"' + $testCommand + '"')
    )
    $process = Start-Process -FilePath 'docker' -ArgumentList $dockerArguments `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $stdout = if (Test-Path $stdoutPath) { [System.IO.File]::ReadAllText($stdoutPath) } else { '' }
    $stderr = if (Test-Path $stderrPath) { [System.IO.File]::ReadAllText($stderrPath) } else { '' }
    $combined = $stdout + $stderr
    [System.IO.File]::WriteAllText($logFile, $combined, [System.Text.UTF8Encoding]::new($false))
    Write-Output $combined
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    if ($process.ExitCode -ne 0) { throw "V50 测试失败，退出码 $($process.ExitCode)" }
} finally {
    $ErrorActionPreference = 'Continue'
    docker compose -f compose.test.yaml down -v
    $ErrorActionPreference = 'Stop'
    Pop-Location
}
