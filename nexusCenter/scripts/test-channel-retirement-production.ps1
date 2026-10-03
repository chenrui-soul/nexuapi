param([string]$BaseUrl = 'https://nexusapi.center')

$ErrorActionPreference = 'Stop'
$sshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519"
$server = 'root@8.218.238.141'
function Invoke-Sql([string]$Sql) {
    $lines = $Sql | & ssh -o BatchMode=yes -i $sshKey $server 'docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -v ON_ERROR_STOP=1'
    if ($LASTEXITCODE -ne 0) { throw 'Production SQL failed' }
    return ($lines -join "`n").Trim()
}

$keyId = [Guid]::NewGuid().ToString()
$results = [Collections.Generic.List[object]]::new()
$created = $false
$revoked = $false
$failure = $null
$evidence = $null
try {
    if ((Invoke-Sql "select to_regclass('public.channel_models') is null;") -ne 't') {
        throw 'V63 retirement is not deployed'
    }
    $parts = (Invoke-Sql @'
select g.id,m.id,u.id from routing_groups g
join ai_models m on m.public_name='gpt-5.6-sol' and m.status='active'
join lateral (
  select candidate.id from users candidate join wallet_accounts w on w.user_id=candidate.id
  where candidate.status='active' and w.permanent_credits+w.expiring_credits>100
  and not exists (select 1 from subscriptions sub where sub.user_id=candidate.id and sub.status='active' and sub.expires_at>now())
  order by w.permanent_credits+w.expiring_credits desc limit 1
) u on true where g.code='gpt_tj' and g.status='active';
'@).Split('|')
    if ($parts.Count -ne 3) { throw 'No eligible funded test user and service group' }
    $groupId, $modelId, $userId = $parts
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $apiKey = 'sk-nx-v1_' + [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    $hmacKey = (& ssh -o BatchMode=yes -i $sshKey $server 'docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1') -join ''
    if ($LASTEXITCODE -ne 0) { throw 'Could not read key signing configuration' }
    $mac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($hmacKey.Trim()))
    try { $hash = [Convert]::ToHexString($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes("api-key:v1:$apiKey"))).ToLowerInvariant() }
    finally { $mac.Dispose(); $hmacKey = $null }
    $prefix = $apiKey.Substring(0,16)
    $suffix = $apiKey.Substring($apiKey.Length-8)
    $created = $true
    Invoke-Sql @"
insert into api_keys (id,user_id,name,key_prefix,key_suffix,key_hash,key_hash_version,status,
default_group_id,service_group_id,allowed_model_ids,allowed_group_ids,ip_allowlist,rpm_limit,tpm_limit,concurrency_limit,credit_limit,expires_at)
values ('$keyId','$userId','temporary channel retirement regression','$prefix','$suffix',decode('$hash','hex'),1,'active',
'$groupId','$groupId','["$modelId"]'::jsonb,'["$groupId"]'::jsonb,'[]'::jsonb,10,100000,1,1,now()+interval '5 minutes');
"@ | Out-Null
    $cases = @(
        @{ name='chat'; path='/v1/chat/completions'; body=@{model='gpt-5.6-sol'; messages=@(@{role='user';content='Only reply OK.'}); stream=$false} },
        @{ name='responses'; path='/v1/responses'; body=@{model='gpt-5.6-sol'; input='Only reply OK.'; stream=$false} },
        @{ name='responses-stream'; path='/v1/responses'; body=@{model='gpt-5.6-sol'; input='Only reply OK.'; stream=$true} }
    )
    foreach ($case in $cases) {
        $requestId = 'req_retire_' + [Guid]::NewGuid().ToString('N')
        $timer = [Diagnostics.Stopwatch]::StartNew()
        try {
            $response = Invoke-WebRequest -Uri ($BaseUrl + $case.path) -Method Post -TimeoutSec 150 -SkipHttpErrorCheck `
                -Headers @{Authorization="Bearer $apiKey";'X-Request-Id'=$requestId} -ContentType 'application/json' `
                -Body ($case.body | ConvertTo-Json -Depth 8 -Compress)
            $body = [string]$response.Content
            $valid = $false
            if ($case.name -eq 'responses-stream') {
                $events = @($body -split "`n" | Where-Object { $_.StartsWith('data: ') -and $_ -notmatch '\[DONE\]' } | ForEach-Object { $_.Substring(6) | ConvertFrom-Json })
                $valid = @($events | Where-Object type -eq 'response.completed').Count -eq 1 -and
                    @($events | Where-Object { $_.type -in @('error','response.failed','response.incomplete') }).Count -eq 0
            } else {
                $json = $body | ConvertFrom-Json
                $valid = if ($case.name -eq 'chat') { -not [string]::IsNullOrWhiteSpace($json.choices[0].message.content) } else { $json.status -eq 'completed' }
            }
            $results.Add([ordered]@{case=$case.name;request_id=$requestId;http_status=[int]$response.StatusCode;duration_ms=$timer.ElapsedMilliseconds;passed=([int]$response.StatusCode -eq 200 -and $valid);body=$body})
        } catch {
            $results.Add([ordered]@{case=$case.name;request_id=$requestId;passed=$false;error=$_.Exception.Message})
        }
    }
    $ids = ($results | ForEach-Object { "'$($_.request_id)'" }) -join ','
    for ($poll=0; $poll -lt 10; $poll++) {
        $evidence = Invoke-Sql @"
select json_build_object('requests',
 (select json_agg(json_build_object('request_id',l.request_id,'status',l.status_code,'error',l.platform_error_code,
 'supplier',s.code,'channel_model_id',l.channel_model_id,'upstream_model',l.upstream_model,'billed_amount',l.billed_amount,
 'reservation_status',w.status)) from request_logs l left join suppliers s on s.id=l.supplier_id
 left join wallet_reservations w on w.request_id=l.request_id where l.request_id in ($ids)),
 'attempts',(select json_agg(json_build_object('request_id',a.request_id,'outcome',a.outcome,'channel_model_id',a.channel_model_id))
 from upstream_attempt_logs a where a.request_id in ($ids)));
"@
        if (@(($evidence | ConvertFrom-Json).requests).Count -eq 3) { break }
        Start-Sleep -Seconds 1
    }
    $facts = $evidence | ConvertFrom-Json
    if (@($facts.requests).Count -ne 3 -or @($facts.requests | Where-Object {
        $_.status -ne 200 -or $_.error -or $_.channel_model_id -or $_.upstream_model -ne 'gpt-5.6-sol' -or
        $_.supplier -ne 'ycyapi' -or $_.reservation_status -ne 'settled'
    }).Count -gt 0) { throw 'Request routing or settlement verification failed' }
    if (@($facts.attempts).Count -lt 3 -or @($facts.attempts | Where-Object channel_model_id).Count -gt 0) { throw 'Attempt evidence missing or contains retired IDs' }
    if (@($results | Where-Object { -not $_.passed }).Count -gt 0) { throw 'One or more response validations failed' }
} catch { $failure = $_.Exception.Message }
finally {
    if ($created) {
        try {
            $state = Invoke-Sql "update api_keys set status='revoked',revoked_at=now(),status_changed_at=now(),version=version+1 where id='$keyId' returning status;"
            $revoked = $state -match 'revoked'
            if (-not $revoked) { $failure = 'Temporary API key revocation not verified' }
        } catch { $failure = 'Temporary API key cleanup failed: ' + $_.Exception.Message }
    }
    $apiKey = $null
    $report = [ordered]@{time=[DateTimeOffset]::Now.ToString('o');endpoint=$BaseUrl;key_revoked=$revoked;failure=$failure;results=@($results.ToArray());evidence=$evidence}
    $path = Join-Path $PSScriptRoot ('log/channel-retirement-production-' + (Get-Date -Format yyyyMMdd-HHmmss) + '.json')
    $report | ConvertTo-Json -Depth 15 | Set-Content -Encoding utf8NoBOM -LiteralPath $path
    $results | ForEach-Object { Write-Output "$($_.case) request=$($_.request_id) HTTP=$($_.http_status) passed=$($_.passed)" }
    Write-Output "key_revoked=$revoked report=$path"
}
if ($failure) { throw $failure }
