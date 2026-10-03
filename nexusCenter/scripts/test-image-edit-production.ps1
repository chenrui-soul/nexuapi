param(
    [string]$Server = "8.218.238.141",
    [string]$SshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519",
    [string]$EndpointRoot = "https://nexusapi.center",
    [string]$ReferenceUrl = "https://nexusapi.center/dashboard-ai-light-v3.png",
    [string]$Prompt = "保留主体，改为黄昏背景"
)

$ErrorActionPreference = "Stop"
$keyId = $null
$secret = $null
$requestId = "req_image_edit_$([guid]::NewGuid().ToString('N'))"

function Invoke-Sql([string]$Query) {
    $result = $Query | & ssh -i $SshKey "root@$Server" `
        "docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -F '|' -v ON_ERROR_STOP=1"
    if ($LASTEXITCODE -ne 0) { throw "Production SQL failed" }
    ($result -join "`n").Trim()
}

try {
    $route = Invoke-Sql @"
select concat_ws('|', g.id, g.code, m.id, u.id)
from routing_groups g
join routing_group_models rgm on rgm.group_id = g.id
join ai_models m on m.id = rgm.model_id
join lateral (
    select candidate.id
    from users candidate
    join wallet_accounts wallet on wallet.user_id = candidate.id
    where candidate.status = 'active'
      and wallet.permanent_credits + wallet.expiring_credits > 100
    order by wallet.permanent_credits + wallet.expiring_credits desc
    limit 1
) u on true
where g.name = 'gpt特价'
  and g.status = 'active'
  and m.public_name = 'gpt-image-2.5'
  and m.status = 'active'
limit 1;
"@
    if ([string]::IsNullOrWhiteSpace($route)) { throw "No active gpt特价/gpt-image-2.5 route" }
    $groupId, $groupCode, $modelId, $userId = $route.Split('|')

    $hmacKey = (& ssh -i $SshKey "root@$Server" `
        "docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1").Trim()
    if ([string]::IsNullOrWhiteSpace($hmacKey)) { throw "API Key HMAC key is unavailable" }

    $randomBytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $secret = "sk-nx-v1_" + [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $mac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($hmacKey))
    try {
        $hashBytes = $mac.ComputeHash([Text.Encoding]::UTF8.GetBytes("api-key:v1:$secret"))
        $hash = ([BitConverter]::ToString($hashBytes) -replace '-', '').ToLowerInvariant()
    } finally {
        $mac.Dispose()
    }
    $keyId = [guid]::NewGuid().ToString()
    $prefix = $secret.Substring(0, 16)
    $suffix = $secret.Substring($secret.Length - 8)
    Invoke-Sql @"
insert into api_keys (
    id, user_id, name, key_prefix, key_suffix, key_hash, key_hash_version, status,
    default_group_id, service_group_id, allowed_model_ids, allowed_group_ids, ip_allowlist,
    rpm_limit, tpm_limit, concurrency_limit, credit_limit, expires_at
) values (
    '$keyId'::uuid, '$userId'::uuid, 'Codex image edit production test', '$prefix', '$suffix',
    decode('$hash', 'hex'), 1, 'active', '$groupId'::uuid, '$groupId'::uuid,
    '["$modelId"]'::jsonb, '["$groupId"]'::jsonb, '[]'::jsonb,
    30, 100000, 1, 1000, now() + interval '10 minutes'
);
"@ | Out-Null

    $reference = Invoke-WebRequest -Uri $ReferenceUrl -Method Head -TimeoutSec 30
    if ($reference.StatusCode -ne 200) { throw "Reference image is unavailable" }

    $payload = @{
        model = "gpt-image-2.5"
        prompt = $Prompt
        aspect_ratio = "1:1"
        images = @($ReferenceUrl)
    } | ConvertTo-Json -Depth 5 -Compress
    $response = Invoke-WebRequest -Uri "$EndpointRoot/v1/images/generations" -Method Post `
        -Headers @{
            Authorization = "Bearer $secret"
            "X-Nexus-Group" = $groupCode
            "X-Request-Id" = $requestId
        } `
        -ContentType "application/json" -Body $payload -TimeoutSec 240 -SkipHttpErrorCheck

    Start-Sleep -Seconds 2
    $audit = Invoke-Sql @"
select concat_ws('|', logs.status_code, coalesce(suppliers.code, ''),
       coalesce(logs.platform_error_code, ''), coalesce(logs.upstream_error_summary, ''))
from request_logs logs
left join suppliers on suppliers.id = logs.supplier_id
where logs.request_id = '$requestId';
"@
    [pscustomobject]@{
        request_id = $requestId
        http_status = [int]$response.StatusCode
        response = [string]$response.Content
        audit = $audit
        reference_url = $ReferenceUrl
    } | ConvertTo-Json -Depth 5
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) { exit 1 }
} finally {
    if ($keyId) {
        Invoke-Sql "update api_keys set status='revoked', revoked_at=now(), status_changed_at=now(), version=version+1 where id='$keyId'::uuid;" | Out-Null
        $state = Invoke-Sql "select status from api_keys where id='$keyId'::uuid;"
        Write-Output "temporary_key_status=$state"
    }
    $secret = $null
}
