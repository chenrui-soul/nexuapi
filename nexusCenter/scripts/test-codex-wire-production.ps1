param(
    [string]$Server = "8.218.238.141",
    [string]$SshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519",
    [string]$Prompt = "只回复：Codex 生产链路测试成功",
    [string]$ExpectedText = "Codex 生产链路测试成功"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $PSScriptRoot "log"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$requestId = "req_codex_wire_$([Guid]::NewGuid().ToString('N'))"
$keyId = [Guid]::NewGuid().ToString()
$logPath = Join-Path $logDir "codex-wire-production-$requestId.log"
$lines = [System.Collections.Generic.List[string]]::new()
function Log([string]$message) { $lines.Add("$([DateTimeOffset]::Now.ToString('o')) $message") }
function Invoke-ProductionSql([string]$sql) {
    $remote = "docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -F '|' -v ON_ERROR_STOP=1"
    $result = $sql | & ssh -i $SshKey "root@$Server" $remote
    if ($LASTEXITCODE -ne 0) { throw "production SQL failed: $LASTEXITCODE" }
    ($result -join "`n").Trim()
}

$configured = $false
$exitCode = 0
try {
    $row = Invoke-ProductionSql @"
select concat_ws('|',g.id,m.id,u.id)
from routing_groups g
join ai_models m on m.public_name='gpt-5.6-sol' and m.status='active'
join lateral (
  select candidate.id from users candidate join wallet_accounts w on w.user_id=candidate.id
  where candidate.status='active' and w.permanent_credits+w.expiring_credits>100
  order by w.permanent_credits+w.expiring_credits desc limit 1
) u on true
where g.code='gpt_tj' and g.status='active';
"@
    $groupId,$modelId,$userId = $row.Split('|')
    $hmacKey = (& ssh -i $SshKey "root@$Server" "docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1").Trim()
    $bytes = [byte[]]::new(32); $rng=[Security.Cryptography.RandomNumberGenerator]::Create(); try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
    $apiKey = "sk-nx-v1_" + [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    $hmac=[Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($hmacKey)); try{$hash=([BitConverter]::ToString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("api-key:v1:$apiKey")))-replace '-','').ToLowerInvariant()}finally{$hmac.Dispose()}
    $prefix=$apiKey.Substring(0,16); $suffix=$apiKey.Substring($apiKey.Length-8)
    Invoke-ProductionSql @"
insert into api_keys (id,user_id,name,key_prefix,key_suffix,key_hash,key_hash_version,status,default_group_id,service_group_id,allowed_model_ids,allowed_group_ids,ip_allowlist,rpm_limit,tpm_limit,concurrency_limit,credit_limit,expires_at)
values ('$keyId'::uuid,'$userId'::uuid,'Codex wire production test','$prefix','$suffix',decode('$hash','hex'),1,'active','$groupId'::uuid,'$groupId'::uuid,'["$modelId"]'::jsonb,'["$groupId"]'::jsonb,'[]'::jsonb,10,1000000,2,5,now()+interval '10 minutes');
"@ | Out-Null
    $configured = $true
    $tempCodexHome = Join-Path $env:TEMP ("nexus-codex-wire-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tempCodexHome | Out-Null
    @{ OPENAI_API_KEY = $apiKey } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $tempCodexHome 'auth.json') -Encoding UTF8
    @"
model_provider = "custom"
model = "gpt-5.6-sol"
disable_response_storage = true
[model_providers.custom]
name = "nexus-production"
base_url = "https://nexusapi.center/v1"
wire_api = "responses"
"@ | Set-Content -LiteralPath (Join-Path $tempCodexHome 'config.toml') -Encoding UTF8
    $env:CODEX_HOME = $tempCodexHome
    $env:OPENAI_API_KEY = $apiKey
    $env:CODEX_TEST_REQUEST_ID = $requestId
    Log "TEST codex_exec request_id=$requestId base_url=https://nexusapi.center/v1 wire_api=responses model=gpt-5.6-sol prompt_chars=$($Prompt.Length)"
    # 临时 CODEX_HOME 不需要插件目录；关闭插件同步，避免外部插件仓库超时干扰模型链路验收。
    $args = @(
        'exec','--ephemeral','--skip-git-repo-check','--ignore-rules','--json',
        '--disable','plugins','--disable','remote_plugin','--disable','recommended_plugins',
        '-m','gpt-5.6-sol',$Prompt
    )
    $output = & codex @args 2>&1
    $codexExit = $LASTEXITCODE
    $text = ($output -join "`n")
    Log "CODEX_EXIT $codexExit"
    Log "CODEX_OUTPUT $text"
    if ($codexExit -ne 0) { throw "codex exec exited with $codexExit" }
    if (-not $text.Contains($ExpectedText)) { throw 'Codex output did not contain expected answer' }
    # Codex generates its own X-Request-Id, so correlate the newest production row
    # by model and the short test window instead of assuming our local marker is sent.
    $row = Invoke-ProductionSql "select concat_ws('|',request_id,status_code,public_model,streaming,retry_count,coalesce(platform_error_code,''),duration_ms) from request_logs where api_key_id='$keyId'::uuid order by created_at desc limit 1;"
    Log "REQUEST_LOG $row"
    if ($row -notmatch '^req_[^|]+\|200\|gpt-5\.6-sol\|t\|[0-3]\|\|[0-9]+$') { throw "request log validation failed: $row" }
    $actualRequestId = $row.Split('|')[0]
    $attempt = Invoke-ProductionSql "select concat_ws('|',s.code,a.outcome,coalesce(a.error_category,''),coalesce(a.error_summary,'')) from upstream_attempt_logs a join suppliers s on s.id=a.supplier_id where a.request_id='$actualRequestId' order by a.attempt_no;"
    Log "UPSTREAM_ATTEMPT $attempt"
    $attemptRows = @($attempt -split "`n" | Where-Object { $_ })
    if ($attemptRows.Count -lt 1 -or $attemptRows[-1] -notmatch '^[^|]+\|success\|\|$') {
        throw "upstream attempt validation failed: $attempt"
    }
    Log 'PASS actual codex exec request completed and was logged as HTTP 200'
} catch { $exitCode=1; Log "FAIL $($_.Exception.Message)" }
finally {
    if ($configured) { try { Invoke-ProductionSql "update api_keys set status='revoked',revoked_at=now(),status_changed_at=now(),version=version+1 where id='$keyId'::uuid;" | Out-Null; Log 'CLEANUP temporary API key revoked' } catch { $exitCode=1; Log "CLEANUP_FAIL $($_.Exception.Message)" } }
    $env:OPENAI_API_KEY=$null; $env:CODEX_TEST_REQUEST_ID=$null
    if ($tempCodexHome -and (Test-Path $tempCodexHome)) { Remove-Item -LiteralPath $tempCodexHome -Recurse -Force -ErrorAction SilentlyContinue }
    $env:CODEX_HOME=$null
    $lines | Set-Content -LiteralPath $logPath -Encoding UTF8
    $lines | ForEach-Object { Write-Output $_ }
}
if ($exitCode) { exit $exitCode }
