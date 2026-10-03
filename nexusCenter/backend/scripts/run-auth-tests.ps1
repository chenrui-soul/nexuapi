param(
    [string]$JdkImage = "docker.1ms.run/library/eclipse-temurin:21-jdk-alpine"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $PSScriptRoot "log"
$logPath = Join-Path $logDirectory "auth-tests.log"

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

# 既支持默认 Alpine JDK 镜像，也支持已经自带 Maven 的官方 Maven 镜像。
$testCommand = "apk add --no-cache maven >/tmp/apk.log && mvn -B clean test"
if ($JdkImage -match '(^|/)maven:') {
    $testCommand = "mvn -B clean test"
}

Push-Location $projectRoot
try {
    # Docker 会把部分正常进度和 JVM 警告写到 stderr；临时关闭 PowerShell 的
    # NativeCommandError 提升，改为只依据进程退出码判断成功或失败。
    $ErrorActionPreference = "Continue"
    docker compose -f compose.test.yaml up -d --wait
    $composeExitCode = $LASTEXITCODE
    if ($composeExitCode -ne 0) {
        throw "Isolated test dependencies failed with exit code $composeExitCode"
    }

    $stdoutPath = Join-Path $logDirectory "auth-tests.stdout.tmp"
    $stderrPath = Join-Path $logDirectory "auth-tests.stderr.tmp"
    $workspaceVolume = '"' + "${projectRoot}:/workspace" + '"'
    $quotedTestCommand = '"' + $testCommand + '"'
    $dockerArguments = @(
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
        "-v", $workspaceVolume,
        "-v", "nexus_maven_repository:/root/.m2",
        "-w", "/workspace",
        $JdkImage,
        "sh", "-lc", $quotedTestCommand
    )

    # Start-Process 将两个输出流直接写入临时文件，避免 Windows PowerShell
    # 把正常的 JVM stderr 警告包装成 ErrorRecord 并中断测试依赖。
    $testProcess = Start-Process -FilePath "docker" -ArgumentList $dockerArguments `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $stdout = if (Test-Path $stdoutPath) { [System.IO.File]::ReadAllText($stdoutPath) } else { "" }
    $stderr = if (Test-Path $stderrPath) { [System.IO.File]::ReadAllText($stderrPath) } else { "" }
    $combinedOutput = $stdout + $stderr
    [System.IO.File]::WriteAllText($logPath, $combinedOutput, [System.Text.UTF8Encoding]::new($false))
    Write-Output $combinedOutput
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

    $testExitCode = $testProcess.ExitCode
    if ($testExitCode -ne 0) {
        throw "Authentication test suite failed with exit code $testExitCode"
    }
} finally {
    $ErrorActionPreference = "Continue"
    docker compose -f compose.test.yaml down -v
    $ErrorActionPreference = "Stop"
    Pop-Location
}
