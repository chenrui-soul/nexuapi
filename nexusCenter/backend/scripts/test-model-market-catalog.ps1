param(
    [string]$MavenImage = "maven:3.9.11-eclipse-temurin-21"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $PSScriptRoot "log"
$catalogLog = Join-Path $logDirectory "model-market-catalog-tests.log"

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

Push-Location $projectRoot
try {
    docker compose -f compose.test.yaml up -d --wait

    # 从既有测试脚本读取同一组隔离环境变量，避免复制或输出任何测试凭证。
    $environmentArguments = @()
    $scriptText = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot "run-auth-tests.ps1"))
    foreach ($match in [regex]::Matches($scriptText, '-e\s+([A-Z0-9_]+)=([^\s`]+)')) {
        $environmentArguments += @('-e', ($match.Groups[1].Value + '=' + $match.Groups[2].Value))
    }
    if ($environmentArguments.Count -eq 0) {
        throw "Unable to load the shared isolated-test environment"
    }

    $dockerArguments = @(
        'run', '--rm',
        '--network', 'nexus-auth-test_default'
    ) + $environmentArguments + @(
        '-v', "${projectRoot}:/workspace",
        '-v', 'nexus_maven_repository:/root/.m2',
        '-w', '/workspace',
        $MavenImage,
        'mvn', '-B', 'clean', 'test'
    )

    # Windows PowerShell 会把原生程序 stderr 包装成 ErrorRecord；这里保留日志但不把 JVM 警告误判为脚本异常。
    $ErrorActionPreference = "Continue"
    docker @dockerArguments | Tee-Object -FilePath $catalogLog
    $dockerExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($dockerExitCode -ne 0) {
        throw "Model market catalog regression suite failed with exit code $dockerExitCode"
    }
    Write-Output "Model market catalog regression log: $catalogLog"
} finally {
    docker compose -f compose.test.yaml down -v
    Pop-Location
}
