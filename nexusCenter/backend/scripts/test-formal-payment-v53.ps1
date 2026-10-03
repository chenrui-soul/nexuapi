$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $project 'scripts/log'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir ('formal-payment-v53-verification-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
& mvn -q -Dtest=AlipayPaymentProviderTest test 2>&1 | Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) { throw "formal payment tests failed; see $log" }
Write-Output "PASS: AlipayProvider tests; log=$log"
