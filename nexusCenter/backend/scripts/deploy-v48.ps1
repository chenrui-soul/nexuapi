param(
    [string]$OldContainer = "nexus-api-app-v47-api-key-usage-20260824-211739",
    [string]$CandidateContainer = "nexus-api-app-v48-candidate-20260825-1144",
    [string]$FinalContainer = "nexus-api-app-v48-ui-functional-20260825-1144",
    [string]$Image = "nexus-api-app:v48-ui-functional-20260825-1144"
)

$ErrorActionPreference = "Stop"
$environmentFile = Join-Path ([IO.Path]::GetTempPath()) ("nexus-v48-" + [Guid]::NewGuid() + ".env")
$oldStopped = $false

try {
    if (-not (docker ps --filter "name=^/$CandidateContainer$" --format "{{.Names}}")) {
        throw "Candidate container is not running: $CandidateContainer"
    }
    if (docker ps -a --filter "name=^/$FinalContainer$" --format "{{.Names}}") {
        throw "Final container already exists: $FinalContainer"
    }

    # 临时环境文件只用于复制候选容器的运行配置，不写入项目目录，并在 finally 中删除。
    $candidateEnvironment = (docker inspect $CandidateContainer | ConvertFrom-Json)[0].Config.Env
    [IO.File]::WriteAllLines($environmentFile, $candidateEnvironment, [Text.UTF8Encoding]::new($false))

    docker stop $OldContainer | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to stop old container" }
    $oldStopped = $true

    docker run -d --name $FinalContainer --network nexus-api_default -p 8080:8080 `
        --restart unless-stopped --env-file $environmentFile $Image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to start final container" }

    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        Start-Sleep -Seconds 2
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health/readiness" -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch {
            # 启动阶段连接失败属于预期情况，继续等待到总超时。
        }
    }
    if (-not $ready) { throw "Final readiness check timed out" }

    docker stop $CandidateContainer | Out-Null
    docker rm $CandidateContainer | Out-Null
    Write-Output "V48 deployment ready: $FinalContainer"
} catch {
    if (docker ps -a --filter "name=^/$FinalContainer$" --format "{{.Names}}") {
        docker stop $FinalContainer 2>$null | Out-Null
        docker rm $FinalContainer 2>$null | Out-Null
    }
    if ($oldStopped) { docker start $OldContainer | Out-Null }
    throw
} finally {
    if (Test-Path -LiteralPath $environmentFile) {
        Remove-Item -LiteralPath $environmentFile -Force
    }
}
