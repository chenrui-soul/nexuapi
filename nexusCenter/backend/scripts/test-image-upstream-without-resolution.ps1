$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$fixture = Get-Content -Raw (Join-Path $root 'references/image-upstream-without-resolution.json') | ConvertFrom-Json
$source = Get-Content -Raw (Join-Path $root 'src/main/java/com/nexusapi/server/modules/gateway/service/OpenAiGatewayService.java')
$method = [regex]::Match($source, 'private Map<String, String> imageUpstreamFields\([\s\S]*?\n    \}')
if (-not $method.Success) { throw 'imageUpstreamFields method not found' }
if ($method.Value -notmatch 'fields\.put\("aspect_ratio"') {
    throw 'aspect_ratio is not forwarded to the upstream payload'
}
if ($method.Value -match 'enumValue\(rawAspectRatio, "aspect_ratio", "auto"') {
    throw 'aspect_ratio still defaults to auto'
}
if ($method.Value -notmatch 'fields\.put\("extra_params"') {
    throw 'extra_params.resolution forwarding was not restored'
}
$logDir = Join-Path $PSScriptRoot 'log'
New-Item -ItemType Directory -Force $logDir | Out-Null
$result = [ordered]@{
    generated_at = [DateTimeOffset]::Now.ToString('o')
    fixture = 'references/image-upstream-without-resolution.json'
    method = 'OpenAiGatewayService.imageUpstreamFields'
    forbidden_fields_absent = $true
    status = 'passed'
}
$path = Join-Path $logDir 'image-upstream-without-resolution.json'
$result | ConvertTo-Json | Set-Content -Encoding UTF8 $path
$result | ConvertTo-Json
