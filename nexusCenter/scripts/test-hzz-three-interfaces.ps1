param(
    [string]$Server = "8.218.238.141",
    [string]$SshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519",
    [string]$EndpointRoot = "https://nexusapi.center"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$referenceDir = Join-Path $projectRoot "references"
$logDir = Join-Path $PSScriptRoot "log"
New-Item -ItemType Directory -Force -Path $referenceDir, $logDir | Out-Null

$responsesFixture = Join-Path $referenceDir "hzz-responses.request.json"
$responsesBody = [ordered]@{
    model = "gpt-5.6-sol"
    input = "请只回复：红蜘蛛 Responses 接口测试成功"
    stream = $false
    max_output_tokens = 64
}
$responsesBody | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8NoBOM $responsesFixture

$imageTaskFixture = Join-Path $referenceDir "hzz-image-task.request.json"
$imageTaskBody = [ordered]@{
    model = "gpt-image-2"
    prompt = "生成一张纯蓝色背景的简洁方形图标"
    n = 1
}
$imageTaskBody | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8NoBOM $imageTaskFixture

function Invoke-ProductionSql {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $remoteCommand = "docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -F '|' -v ON_ERROR_STOP=1"
    $result = $Sql | & ssh -i $SshKey "root@$Server" $remoteCommand
    if ($LASTEXITCODE -ne 0) { throw "Production SQL command failed with exit code $LASTEXITCODE" }
    return ($result -join "`n").Trim()
}

function Add-TestLog {
    param([string]$Message)
    $script:logLines.Add("$([DateTimeOffset]::Now.ToString('o')) $Message")
}

function Invoke-JsonRequest {
    param([string]$Path, [string]$RequestId, [string]$Body)
    return Invoke-WebRequest -Uri "$EndpointRoot$Path" -Method Post `
        -Headers @{ Authorization = "Bearer $script:apiKey"; "X-Request-Id" = $RequestId; "X-Nexus-Group" = "gpt_tj" } `
        -ContentType "application/json" -Body $Body -TimeoutSec 180 -SkipHttpErrorCheck
}

function Invoke-RouteCheck {
    param([string]$RequestId)
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Seconds 1
        $row = Invoke-ProductionSql @"
select coalesce(string_agg(concat_ws(':', ual.attempt_no, s.code, c.operation_code, ual.outcome, coalesce(ual.upstream_status::text, '')), ',' order by ual.attempt_no), '')
from upstream_attempt_logs ual
left join suppliers s on s.id = ual.supplier_id
left join channels c on c.id = ual.channel_id
where ual.request_id = '$RequestId';
select concat_ws('|', coalesce(status_code::text, ''), coalesce(public_model, ''))
from request_logs where request_id = '$RequestId';
"@
        if (-not [string]::IsNullOrWhiteSpace($row)) { return $row }
    }
    return ""
}

$logLines = [System.Collections.Generic.List[string]]::new()
$testKeyId = [Guid]::NewGuid().ToString()
    $originalStatus = $null
$exitCode = 0
$results = @()

try {
    $configuration = Invoke-ProductionSql @"
select concat_ws('|',
    g.id,
    text_model.id,
    image_model.id,
    u.id
)
from routing_groups g
join ai_models text_model on text_model.public_name = 'gpt-5.6-sol' and text_model.capability_type = 'text' and text_model.status = 'active'
join ai_models image_model on image_model.public_name = 'gpt-image-2' and image_model.capability_type = 'image' and image_model.status = 'active'
join lateral (
    select candidate.id
    from users candidate
    join wallet_accounts wallet on wallet.user_id = candidate.id
    where candidate.status = 'active'
    order by wallet.permanent_credits + wallet.expiring_credits desc
    limit 1
) u on true
where g.code = 'gpt_tj' and g.status = 'active';
"@
    $parts = $configuration.Split('|')
    if ($parts.Count -ne 4) { throw "Production test prerequisites are incomplete: gpt_tj/text/image/user" }
    $groupId, $textModelId, $imageModelId, $userId = $parts

    $originalStatus = Invoke-ProductionSql "select id || '|' || status from routing_group_suppliers where group_id = '$groupId'::uuid order by id;"
    # Temporarily isolate the Red Spider supplier so every request is a direct supplier test.
    Invoke-ProductionSql "update routing_group_suppliers set status = case when supplier_id = (select id from suppliers where code='hzz') then 'active' else 'disabled' end, updated_at=now(), version=version+1 where group_id = '$groupId'::uuid;" | Out-Null

    $hmacKeyBase64 = (& ssh -i $SshKey "root@$Server" "docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1").Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($hmacKeyBase64)) { throw "Unable to load production API key HMAC configuration" }
    $randomBytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $payload = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $script:apiKey = "sk-nx-v1_$payload"
    $keyPrefix = $script:apiKey.Substring(0, 16)
    $keySuffix = $script:apiKey.Substring($script:apiKey.Length - 8)
    $hmac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($hmacKeyBase64))
    try {
        $keyHashHex = [Convert]::ToHexString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("api-key:v1:$($script:apiKey)"))).ToLowerInvariant()
    } finally { $hmac.Dispose() }

    Invoke-ProductionSql @"
insert into api_keys (
    id, user_id, name, key_prefix, key_suffix, key_hash, key_hash_version,
    status, default_group_id, service_group_id, allowed_model_ids,
    allowed_group_ids, ip_allowlist, rpm_limit, tpm_limit,
    concurrency_limit, credit_limit, expires_at
) values (
    '$testKeyId'::uuid, '$userId'::uuid, 'Codex 红蜘蛛三接口临时测试',
    '$keyPrefix', '$keySuffix', decode('$keyHashHex', 'hex'), 1,
    'active', '$groupId'::uuid, '$groupId'::uuid,
    '["$textModelId", "$imageModelId"]'::jsonb, '["$groupId"]'::jsonb,
    '[]'::jsonb, 10, 20000, 3, 100, now() + interval '15 minutes'
);
"@ | Out-Null

    $tests = @(
        @{ Name = "responses"; Path = "/v1/responses"; RequestId = "req_codex_hzz_responses_$([Guid]::NewGuid().ToString('N'))"; Body = (Get-Content -Raw -Encoding utf8 $responsesFixture) },
        @{ Name = "image_tasks"; Path = "/v1/images/tasks"; RequestId = "req_codex_hzz_image_tasks_$([Guid]::NewGuid().ToString('N'))"; Body = (Get-Content -Raw -Encoding utf8 $imageTaskFixture) }
    )
    foreach ($test in $tests) {
        Add-TestLog "TEST name=$($test.Name) request_id=$($test.RequestId) path=$($test.Path)"
        $response = Invoke-JsonRequest -Path $test.Path -RequestId $test.RequestId -Body $test.Body
        $snippet = [string]$response.Content
        if ($snippet.Length -gt 6000) { $snippet = $snippet.Substring(0, 6000) + "...<truncated>" }
        Add-TestLog "HTTP name=$($test.Name) status=$($response.StatusCode) response=$snippet"
        $route = Invoke-RouteCheck -RequestId $test.RequestId
        Add-TestLog "ROUTE name=$($test.Name) $route"
        $results += [pscustomobject]@{ Name = $test.Name; Status = $response.StatusCode; Route = $route }
    }

    $imageRequestId = "req_codex_hzz_image_generations_$([Guid]::NewGuid().ToString('N'))"
    Add-TestLog "TEST name=image_generations request_id=$imageRequestId path=/v1/images/generations"
    $imageResponse = Invoke-WebRequest -Uri "$EndpointRoot/v1/images/generations" -Method Post `
        -Headers @{ Authorization = "Bearer $script:apiKey"; "X-Request-Id" = $imageRequestId; "X-Nexus-Group" = "gpt_tj" } `
        -Form @{ model = "gpt-image-2"; prompt = "生成一张纯蓝色背景的简洁方形图标"; n = "1" } `
        -TimeoutSec 180 -SkipHttpErrorCheck
    $imageSnippet = [string]$imageResponse.Content
    if ($imageSnippet.Length -gt 6000) { $imageSnippet = $imageSnippet.Substring(0, 6000) + "...<truncated>" }
    Add-TestLog "HTTP name=image_generations status=$($imageResponse.StatusCode) response=$imageSnippet"
    $imageRoute = Invoke-RouteCheck -RequestId $imageRequestId
    Add-TestLog "ROUTE name=image_generations $imageRoute"
    $results += [pscustomobject]@{ Name = "image_generations"; Status = $imageResponse.StatusCode; Route = $imageRoute }

    foreach ($result in $results) {
        if ([int]$result.Status -lt 200 -or [int]$result.Status -ge 300) { throw "$($result.Name) returned HTTP $($result.Status)" }
        if ($result.Route -notmatch '(^|,)\d+:hzz:[^:]+:(success|completed)(:|,|$)') {
            throw "$($result.Name) route validation failed: $($result.Route)"
        }
    }
    Add-TestLog "PASS all three interfaces returned 2xx and routed through supplier=hzz"
}
catch {
    $exitCode = 1
    Add-TestLog "FAIL $($_.Exception.Message)"
}
finally {
    if ($testKeyId) {
        try {
            Invoke-ProductionSql @"
update api_keys
set status = 'revoked', revoked_at = now(), status_changed_at = now(), version = version + 1
where id = '$testKeyId'::uuid;
"@ | Out-Null
            Add-TestLog "CLEANUP temporary API key revoked"
        } catch {
            $exitCode = 1
            Add-TestLog "CLEANUP_FAIL $($_.Exception.Message)"
        }
    }
    if ($originalStatus) {
        try {
            foreach ($line in ($originalStatus -split "`n")) {
                $statusParts = $line.Split('|')
                if ($statusParts.Count -eq 2) {
                    Invoke-ProductionSql "update routing_group_suppliers set status='$($statusParts[1])', updated_at=now(), version=version+1 where id='$($statusParts[0])'::uuid;" | Out-Null
                }
            }
            Add-TestLog "CLEANUP original supplier route statuses restored"
        } catch {
            $exitCode = 1
            Add-TestLog "CLEANUP_ROUTE_FAIL $($_.Exception.Message)"
        }
    }
    $script:apiKey = $null
    $hmacKeyBase64 = $null
}

$logPath = Join-Path $logDir "hzz-three-interfaces-$([DateTime]::Now.ToString('yyyyMMdd-HHmmss')).log"
$logLines | Set-Content -Encoding utf8NoBOM $logPath
$logLines | ForEach-Object { Write-Output $_ }
Write-Output "Log: $logPath"
if ($exitCode -ne 0) { exit $exitCode }
