param(
    [switch]$Full,
    [string]$JdkImage = "maven:3.9.11-eclipse-temurin-21-alpine"
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $PSScriptRoot 'log'
$logFile = Join-Path $logDirectory $(if ($Full) {
    'subscription-concurrency-v52-full-test.log'
} else {
    'subscription-concurrency-v52-test.log'
})
$caseFile = Join-Path $projectRoot 'references/subscription-concurrency-v52-cases.json'

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$cases = Get-Content -Raw $caseFile | ConvertFrom-Json
$requiredCases = @('subscription_snapshot', 'shared_concurrency', 'atomic_rejection', 'lease_release')
$actualCases = @($cases.ground_truth.case)
if (@($requiredCases | Where-Object { $_ -notin $actualCases }).Count -ne 0) {
    throw 'V52 并发测试 Ground Truth 不完整'
}

Push-Location $projectRoot
try {
    docker compose -f compose.test.yaml up -d --wait
    if ($LASTEXITCODE -ne 0) { throw '隔离测试依赖启动失败' }

    $tests = 'OpenAiGatewayIntegrationTest,SubscriptionBillingIntegrationTest,SchemaDocumentationIntegrationTest'
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
        '-v', "${projectRoot}:/workspace",
        '-v', 'nexus_maven_repository:/root/.m2',
        '-w', '/workspace',
        $JdkImage,
        'mvn', '-B'
    )
    if (-not $Full) { $dockerArguments += "-Dtest=$tests" }
    $dockerArguments += 'test'
    & docker @dockerArguments 2>&1 | Tee-Object -FilePath $logFile
    if ($LASTEXITCODE -ne 0) { throw "V52 套餐并发测试失败，日志：$logFile" }
} finally {
    docker compose -f compose.test.yaml down -v
    Pop-Location
}
