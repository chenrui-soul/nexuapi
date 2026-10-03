param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [int]$Requests = 100,
    [int]$Concurrency = 10
)

$ErrorActionPreference = 'Stop'
$uri = "$BaseUrl/actuator/health/readiness"
$started = [DateTimeOffset]::UtcNow
$results = 1..$Requests | ForEach-Object -Parallel {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $using:uri -TimeoutSec 15
        [pscustomobject]@{ status = [int]$response.StatusCode; duration_ms = [long]$timer.Elapsed.TotalMilliseconds; error = $null }
    } catch {
        [pscustomobject]@{ status = 0; duration_ms = [long]$timer.Elapsed.TotalMilliseconds; error = 'request_failed' }
    } finally { $timer.Stop() }
} -ThrottleLimit $Concurrency
$durations = @($results | ForEach-Object duration_ms | Sort-Object)
function Percentile([long[]]$values, [double]$p) {
    if ($values.Count -eq 0) { return 0 }
    $index = [Math]::Min($values.Count - 1, [Math]::Max(0, [Math]::Ceiling($values.Count * $p) - 1))
    return $values[$index]
}
$elapsed = ([DateTimeOffset]::UtcNow - $started).TotalSeconds
$report = [ordered]@{
    generated_at = [DateTimeOffset]::Now.ToString('o')
    target = $uri
    requests = $Requests
    concurrency = $Concurrency
    elapsed_seconds = [Math]::Round($elapsed, 3)
    rps = if ($elapsed -gt 0) { [Math]::Round($Requests / $elapsed, 2) } else { 0 }
    success_count = @($results | Where-Object { $_.status -ge 200 -and $_.status -lt 300 }).Count
    error_count = @($results | Where-Object { $_.status -lt 200 -or $_.status -ge 300 }).Count
    p50_ms = Percentile $durations 0.50
    p95_ms = Percentile $durations 0.95
    p99_ms = Percentile $durations 0.99
}
$json = $report | ConvertTo-Json
$path = Join-Path $PSScriptRoot ('log/pressure-baseline-v54-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
$json | Set-Content -LiteralPath $path -Encoding UTF8
$json
"LOG_PATH=$path"
