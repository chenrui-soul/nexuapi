[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$AppContainer = 'nexus-api-app-v42-interface-doc-runtime-decoupling-20260823-233606',
    [string]$DbContainer = 'nexus-api-postgres-1',
    [string]$DbUser = 'nexus',
    [string]$DbName = 'nexus_api',
    [string[]]$CaseIds = @(),
    [string]$MediaGroupId,
    [string]$VideoModel,
    [string]$VideoDetailModel,
    [string]$ChatModel,
    [string]$FixturePath,
    [string]$LogLabel = 'public-capabilities-v42'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$fixturePath = if ([string]::IsNullOrWhiteSpace($FixturePath)) {
    Join-Path $root 'references/public-capabilities-v42-validation.json'
} else {
    $FixturePath
}
$logDirectory = Join-Path $PSScriptRoot 'log'
$logPath = Join-Path $logDirectory ($LogLabel + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
$fixture = Get-Content -Raw $fixturePath | ConvertFrom-Json -Depth 30
if (-not [string]::IsNullOrWhiteSpace($MediaGroupId)) {
    $fixture.groups.media = $MediaGroupId
}
if (-not [string]::IsNullOrWhiteSpace($VideoModel)) {
    $videoCase = $fixture.cases | Where-Object { $_.id -eq 'video_create' } | Select-Object -First 1
    if ($videoCase) { $videoCase.model = $VideoModel; $videoCase.body.model = $VideoModel }
}
if (-not [string]::IsNullOrWhiteSpace($ChatModel)) {
    $chatCase = $fixture.cases | Where-Object { $_.id -eq 'chat' } | Select-Object -First 1
    if ($chatCase) { $chatCase.model = $ChatModel; $chatCase.body.model = $ChatModel }
}
$userId = [string]$fixture.environment.user_id
$createdKeyIds = [System.Collections.Generic.List[string]]::new()
$secrets = @{}
$results = [System.Collections.Generic.List[object]]::new()
$audioPath = Join-Path ([System.IO.Path]::GetTempPath()) ('nexus-public-capabilities-' + [guid]::NewGuid().ToString('N') + '.wav')
$videoTaskId = $null

New-Item -ItemType Directory -Force $logDirectory | Out-Null

function Invoke-Psql {
    param([Parameter(Mandatory)][string]$Sql)
    $output = $Sql | & docker exec -i $DbContainer psql -X -q -v ON_ERROR_STOP=1 -U $DbUser -d $DbName -At -P pager=off
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL command failed' }
    return ($output -join "`n").Trim()
}

function ConvertTo-Hex {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    return -join ($Bytes | ForEach-Object { $_.ToString('x2') })
}

function New-TemporaryApiKey {
    param([Parameter(Mandatory)][string]$GroupId, [Parameter(Mandatory)][string]$Label)

    $containerJson = & docker inspect $AppContainer | ConvertFrom-Json -Depth 20
    $hmacEntry = $containerJson[0].Config.Env | Where-Object { $_ -like 'NEXUS_API_KEY_HMAC_KEY_V1=*' } | Select-Object -First 1
    if (-not $hmacEntry) { throw 'App container does not expose API key HMAC v1 configuration' }
    $hmacBase64 = $hmacEntry.Substring($hmacEntry.IndexOf('=') + 1)
    $hmacKey = [Convert]::FromBase64String($hmacBase64)
    if ($hmacKey.Length -ne 32) { throw 'API key HMAC v1 must decode to 32 bytes' }

    $random = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $encoded = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $secret = 'sk-nx-v1_' + $encoded
    $message = [Text.Encoding]::UTF8.GetBytes('api-key:v1:' + $secret)
    $hasher = [Security.Cryptography.HMACSHA256]::new($hmacKey)
    try { $digest = $hasher.ComputeHash($message) } finally { $hasher.Dispose() }
    [Array]::Clear($hmacKey, 0, $hmacKey.Length)
    $keyId = [guid]::NewGuid().ToString()
    $prefix = $secret.Substring(0, 16)
    $suffix = $secret.Substring($secret.Length - 8)
    $hashHex = ConvertTo-Hex $digest
    $safeLabel = ($Label -replace '[^A-Za-z0-9_-]', '-')
    $sql = @"
INSERT INTO api_keys (
    id, user_id, name, key_prefix, key_suffix, key_hash, key_hash_version, status,
    default_group_id, service_group_id, allowed_model_ids, allowed_group_ids, ip_allowlist,
    expires_at
) VALUES (
    '$keyId', '$userId', 'Codex capability validation $safeLabel', '$prefix', '$suffix',
    decode('$hashHex','hex'), 1, 'active', '$GroupId', '$GroupId', '[]'::jsonb,
    '[`"$GroupId`"]'::jsonb, '[]'::jsonb, now() + interval '2 hours'
);
"@
    Invoke-Psql $sql | Out-Null
    $createdKeyIds.Add($keyId)
    return $secret
}

function Get-SafeError {
    param([object]$Response, [object]$Exception)
    $status = 0
    $text = $null
    if ($Response) {
        $status = [int]$Response.StatusCode
        $text = [string]$Response.Content
    } elseif ($Exception -and $Exception.Response) {
        $status = [int]$Exception.Response.StatusCode
    }
    $code = $null
    $message = $null
    if ($text) {
        try {
            $json = $text | ConvertFrom-Json -Depth 20
            $code = [string]($json.error.code ?? $json.code ?? $json.error.type)
            $message = [string]($json.error.message ?? $json.message)
        } catch { }
    }
    if ($message -and $message.Length -gt 240) { $message = $message.Substring(0, 240) }
    return [ordered]@{ status = $status; code = $code; message = $message }
}

function Get-RequestEvidence {
    param([Parameter(Mandatory)][string]$RequestId)
    $sql = @"
SELECT coalesce(row_to_json(e)::text, '')
FROM (
    SELECT r.request_id, r.status_code, r.platform_error_code, r.duration_ms,
           r.input_tokens, r.output_tokens, r.cached_tokens, r.billed_amount,
           r.streaming, r.retry_count, r.public_model, r.upstream_model,
           r.upstream_error_summary, r.supplier_error_category,
           d.engine_mode, d.billing_type, d.base_unit_price, d.effective_unit_price,
           d.usage_snapshot, d.calculated_amount, d.settled_amount, d.status AS billing_status
      FROM request_logs r
      LEFT JOIN request_billing_details d ON d.request_id = r.request_id
     WHERE r.request_id = '$RequestId'
     ORDER BY r.created_at DESC
     LIMIT 1
) e;
"@
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $json = Invoke-Psql $sql
        if ($json) { return ($json | ConvertFrom-Json -Depth 30) }
        Start-Sleep -Milliseconds 250
    }
    return $null
}

function Find-TaskId {
    param([object]$Json)
    if (-not $Json) { return $null }
    foreach ($name in @('task_id', 'id', 'taskId')) {
        $property = $Json.PSObject.Properties[$name]
        if ($property -and $property.Value -is [string] -and $property.Value) { return [string]$property.Value }
    }
    foreach ($name in @('data', 'result', 'task')) {
        $property = $Json.PSObject.Properties[$name]
        if ($property) {
            $nested = Find-TaskId $property.Value
            if ($nested) { return $nested }
        }
    }
    return $null
}

function Add-Result {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$RequestId,
        [Parameter(Mandatory)][int]$HttpStatus,
        [Parameter(Mandatory)][bool]$ContractPassed,
        [object]$Error,
        [object]$Extra
    )
    $evidence = if ($Id -eq 'models') { $null } else { Get-RequestEvidence $RequestId }
    $runtimePassed = $HttpStatus -ge 200 -and $HttpStatus -lt 300 -and $ContractPassed
    $loggedPassed = $Id -eq 'models' -or ($evidence -and [int]$evidence.status_code -ge 200 -and [int]$evidence.status_code -lt 300)
    $results.Add([ordered]@{
        id = $Id
        method = $Method
        path = $Path
        request_id = $RequestId
        http_status = $HttpStatus
        contract_passed = $ContractPassed
        request_log_passed = [bool]$loggedPassed
        passed = [bool]($runtimePassed -and $loggedPassed)
        error = $Error
        evidence = $evidence
        extra = $Extra
    })
}

function Invoke-JsonCase {
    param([object]$Case, [string]$Secret)
    $requestId = 'cap-' + $Case.id + '-' + [guid]::NewGuid().ToString('N')
    $headers = @{ Authorization = 'Bearer ' + $Secret; 'X-Request-Id' = $requestId }
    $response = $null
    $errorInfo = $null
    $contract = $false
    try {
        $response = Invoke-WebRequest -Uri ($BaseUrl + $Case.path) -Method $Case.method -Headers $headers `
            -ContentType 'application/json' -Body ($Case.body | ConvertTo-Json -Depth 30 -Compress) `
            -SkipHttpErrorCheck -TimeoutSec 240
        if ([int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 300) {
            $json = $response.Content | ConvertFrom-Json -Depth 40
            switch ($Case.id) {
                'chat' { $contract = -not [string]::IsNullOrWhiteSpace([string]$json.choices[0].message.content) }
                'responses' { $contract = $null -ne $json }
                'embeddings' { $contract = $json.data.Count -gt 0 -and $json.data[0].embedding.Count -gt 0 }
                'image_task' {
                    $task = Find-TaskId $json
                    $contract = -not [string]::IsNullOrWhiteSpace($task)
                }
                'video_create' {
                    $script:videoTaskId = Find-TaskId $json
                    $contract = -not [string]::IsNullOrWhiteSpace($script:videoTaskId)
                }
                default { $contract = $null -ne $json }
            }
        } else { $errorInfo = Get-SafeError $response $null }
    } catch {
        $errorInfo = Get-SafeError $response $_.Exception
    }
    $status = if ($response) { [int]$response.StatusCode } else { [int]($errorInfo.status ?? 0) }
    Add-Result $Case.id $Case.method $Case.path $requestId $status $contract $errorInfo $null
}

try {
    foreach ($groupProperty in $fixture.groups.PSObject.Properties) {
        $secrets[$groupProperty.Name] = New-TemporaryApiKey ([string]$groupProperty.Value) $groupProperty.Name
    }

    foreach ($case in $fixture.cases) {
        if ($CaseIds.Count -gt 0 -and [string]$case.id -notin $CaseIds) { continue }
        $secret = [string]$secrets[[string]$case.group]
        if ($case.id -eq 'models') {
            $requestId = 'cap-models-' + [guid]::NewGuid().ToString('N')
            $response = Invoke-WebRequest -Uri ($BaseUrl + $case.path) -Method Get `
                -Headers @{ Authorization = 'Bearer ' + $secret; 'X-Request-Id' = $requestId } `
                -SkipHttpErrorCheck -TimeoutSec 60
            $contract = $false
            $errorInfo = $null
            if ([int]$response.StatusCode -eq 200) {
                $json = $response.Content | ConvertFrom-Json -Depth 30
                $contract = @($json.data | Where-Object { $_.id -eq 'gpt-5.4-mini' }).Count -gt 0
            } else { $errorInfo = Get-SafeError $response $null }
            Add-Result $case.id $case.method $case.path $requestId ([int]$response.StatusCode) $contract $errorInfo $null
            continue
        }

        if ($case.id -eq 'image_generation') {
            $requestId = 'cap-image-generation-' + [guid]::NewGuid().ToString('N')
            $headers = @{ Authorization = 'Bearer ' + $secret; 'X-Request-Id' = $requestId }
            $form = @{}
            foreach ($property in $case.form.PSObject.Properties) { $form[$property.Name] = [string]$property.Value }
            $response = $null
            $errorInfo = $null
            $contract = $false
            try {
                $response = Invoke-WebRequest -Uri ($BaseUrl + $case.path) -Method Post -Headers $headers `
                    -Form $form -SkipHttpErrorCheck -TimeoutSec 300
                if ([int]$response.StatusCode -eq 200) {
                    $json = $response.Content | ConvertFrom-Json -Depth 40
                    $contract = $json.data.Count -gt 0
                } else { $errorInfo = Get-SafeError $response $null }
            } catch { $errorInfo = Get-SafeError $response $_.Exception }
            $status = if ($response) { [int]$response.StatusCode } else { [int]($errorInfo.status ?? 0) }
            Add-Result $case.id $case.method $case.path $requestId $status $contract $errorInfo $null
            continue
        }

        if ($case.id -eq 'speech') {
            $requestId = 'cap-speech-' + [guid]::NewGuid().ToString('N')
            $headers = @{ Authorization = 'Bearer ' + $secret; 'X-Request-Id' = $requestId }
            $response = $null
            $errorInfo = $null
            $contract = $false
            try {
                $response = Invoke-WebRequest -Uri ($BaseUrl + $case.path) -Method Post -Headers $headers `
                    -ContentType 'application/json' -Body ($case.body | ConvertTo-Json -Depth 20 -Compress) `
                    -OutFile $audioPath -PassThru -SkipHttpErrorCheck -TimeoutSec 240
                $contract = [int]$response.StatusCode -eq 200 -and (Test-Path $audioPath) -and (Get-Item $audioPath).Length -gt 100
                if (-not $contract) { $errorInfo = [ordered]@{ status = [int]$response.StatusCode; code = $null; message = '音频响应为空或过短' } }
            } catch { $errorInfo = Get-SafeError $response $_.Exception }
            $status = if ($response) { [int]$response.StatusCode } else { [int]($errorInfo.status ?? 0) }
            $bytes = if (Test-Path $audioPath) { (Get-Item $audioPath).Length } else { 0 }
            Add-Result $case.id $case.method $case.path $requestId $status $contract $errorInfo @{ audio_bytes = $bytes }
            continue
        }

        if ($case.id -eq 'transcription') {
            if (-not (Test-Path $audioPath) -or (Get-Item $audioPath).Length -le 100) {
                # Speech 上游不可用时，生成 1 秒本地 WAV，避免把转写接口误判为“未测试”。
                & ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:duration=1' `
                    -ar 16000 -ac 1 -c:a pcm_s16le -y $audioPath
                if ($LASTEXITCODE -ne 0 -or -not (Test-Path $audioPath)) {
                    Add-Result $case.id $case.method $case.path ('cap-transcription-skipped-' + [guid]::NewGuid().ToString('N')) 0 $false `
                        @{ status = 0; code = 'TEST_FIXTURE_FAILED'; message = '无法生成转写测试音频' } $null
                    continue
                }
            }
            $requestId = 'cap-transcription-' + [guid]::NewGuid().ToString('N')
            $headers = @{ Authorization = 'Bearer ' + $secret; 'X-Request-Id' = $requestId }
            $response = $null
            $errorInfo = $null
            $contract = $false
            try {
                $response = Invoke-WebRequest -Uri ($BaseUrl + $case.path) -Method Post -Headers $headers `
                    -Form @{ file = Get-Item $audioPath; model = 'whisper-1'; language = 'zh'; response_format = 'json' } `
                    -SkipHttpErrorCheck -TimeoutSec 240
                $contract = [int]$response.StatusCode -eq 200 -and -not [string]::IsNullOrWhiteSpace([string]$response.Content)
                if (-not $contract) { $errorInfo = Get-SafeError $response $null }
            } catch { $errorInfo = Get-SafeError $response $_.Exception }
            $status = if ($response) { [int]$response.StatusCode } else { [int]($errorInfo.status ?? 0) }
            Add-Result $case.id $case.method $case.path $requestId $status $contract $errorInfo $null
            continue
        }

        if ($case.id -eq 'video_list') {
            $requestId = 'cap-video-list-' + [guid]::NewGuid().ToString('N')
            $response = Invoke-WebRequest -Uri ($BaseUrl + $case.path) -Method Get `
                -Headers @{ Authorization = 'Bearer ' + $secret; 'X-Request-Id' = $requestId } `
                -SkipHttpErrorCheck -TimeoutSec 120
            $contract = $false
            $errorInfo = $null
            if ([int]$response.StatusCode -eq 200) {
                try { $null = $response.Content | ConvertFrom-Json -Depth 30; $contract = $true } catch { }
            } else { $errorInfo = Get-SafeError $response $null }
            Add-Result $case.id $case.method $case.path $requestId ([int]$response.StatusCode) $contract $errorInfo $null
            continue
        }

        if ($case.id -eq 'video_detail') {
            if ([string]::IsNullOrWhiteSpace($videoTaskId)) {
                # 创建失败时仍用合法占位任务号探测详情路由，区分“入口未测试”和“上游不可用”。
                $videoTaskId = 'validation-probe-task'
            }
            $detailModel = if ([string]::IsNullOrWhiteSpace($VideoDetailModel)) {
                [string]$case.model
            } else {
                $VideoDetailModel
            }
            $path = '/v1/videos/' + [Uri]::EscapeDataString($videoTaskId) + '?model=' + [Uri]::EscapeDataString($detailModel)
            $requestId = 'cap-video-detail-' + [guid]::NewGuid().ToString('N')
            $response = Invoke-WebRequest -Uri ($BaseUrl + $path) -Method Get `
                -Headers @{ Authorization = 'Bearer ' + $secret; 'X-Request-Id' = $requestId } `
                -SkipHttpErrorCheck -TimeoutSec 120
            $contract = $false
            $errorInfo = $null
            if ([int]$response.StatusCode -eq 200) {
                try { $null = $response.Content | ConvertFrom-Json -Depth 30; $contract = $true } catch { }
            } else { $errorInfo = Get-SafeError $response $null }
            Add-Result $case.id $case.method $path $requestId ([int]$response.StatusCode) $contract $errorInfo @{ task_id_present = $true }
            continue
        }

        Invoke-JsonCase $case $secret
    }
} finally {
    foreach ($keyId in $createdKeyIds) {
        try {
            Invoke-Psql "UPDATE api_keys SET status='revoked', revoked_at=now(), status_changed_at=now(), version=version+1 WHERE id='$keyId' AND status <> 'revoked';" | Out-Null
        } catch { }
    }
    foreach ($name in @($secrets.Keys)) { $secrets[$name] = $null }
    if (Test-Path $audioPath) { Remove-Item -LiteralPath $audioPath -Force }

    $summary = [ordered]@{
        generated_at = (Get-Date).ToString('o')
        base_url = $BaseUrl
        app_container = $AppContainer
        total = $results.Count
        passed = @($results | Where-Object passed).Count
        failed = @($results | Where-Object { -not $_.passed }).Count
        temporary_keys_created = $createdKeyIds.Count
        temporary_keys_revoked = (Invoke-Psql "SELECT count(*) FROM api_keys WHERE id IN ('$(($createdKeyIds -join "','"))') AND status='revoked';")
        results = $results
    }
    $summary | ConvertTo-Json -Depth 40 | Set-Content -Encoding UTF8 $logPath
    $summary | ConvertTo-Json -Depth 8
    Write-Output ('LOG_PATH=' + $logPath)
}
