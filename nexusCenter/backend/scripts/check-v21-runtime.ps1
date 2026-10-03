param(
    [string]$AppContainer = "nexus-api-app-v16-wave10a",
    [string]$DatabaseContainer = "nexus-api-postgres-1",
    [string]$DatabaseName = "nexus_api",
    [string]$DatabaseUser = "nexus",
    [int]$LogWindowMinutes = 30
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$contractPath = Join-Path $projectRoot "references\v21-runtime-contract.json"
$logDirectory = Join-Path $PSScriptRoot "log"
$logPath = Join-Path $logDirectory "v21-runtime-check.log"
$contract = Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

# 只读取 Flyway 最新版本和固定异常类型，不读取请求正文、Cookie 或任何凭证。
$versionText = docker exec $DatabaseContainer psql -U $DatabaseUser -d $DatabaseName -Atc `
    "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1"
if ($LASTEXITCODE -ne 0 -or -not $versionText) {
    throw "Unable to read the current Flyway version"
}

$currentVersion = [int]$versionText.Trim()
$since = "${LogWindowMinutes}m"
$recentLogs = docker logs --since $since $AppContainer 2>&1
$missingEndpoint = @($recentLogs | Select-String -SimpleMatch $contract.endpoint_path_suffix | `
    Where-Object { $_.Line -like "*$($contract.missing_endpoint_exception)*" }).Count -gt 0
$compatible = $currentVersion -ge [int]$contract.minimum_flyway_version -and -not $missingEndpoint

$result = [ordered]@{
    checked_at = (Get-Date).ToString("s")
    feature = $contract.feature
    current_flyway_version = $currentVersion
    minimum_flyway_version = [int]$contract.minimum_flyway_version
    missing_endpoint_observed = $missingEndpoint
    compatible = $compatible
}

$json = $result | ConvertTo-Json
[System.IO.File]::WriteAllText($logPath, $json, [System.Text.UTF8Encoding]::new($false))
Write-Output $json

if (-not $compatible) {
    exit 1
}
