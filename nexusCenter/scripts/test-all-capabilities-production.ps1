param(
    [string]$Server = "8.218.238.141",
    [string]$SshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519",
    [string]$EndpointRoot = "https://nexusapi.center"
)

$ErrorActionPreference = "Stop"
$logDir = Join-Path $PSScriptRoot "log"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = [System.Collections.Generic.List[string]]::new()
function Log([string]$s) { $log.Add("$([DateTimeOffset]::Now.ToString('o')) $s") }
function Sql([string]$q) {
    $r = $q | & ssh -i $SshKey "root@$Server" "docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -F '|' -v ON_ERROR_STOP=1"
    if ($LASTEXITCODE) { throw "SQL failed" }
    ($r -join "`n").Trim()
}
function New-Key([string]$groupId,[string]$userId) {
    $hmacKey = (& ssh -i $SshKey "root@$Server" "docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1").Trim()
    $bytes = [byte[]]::new(32); [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $secret = "sk-nx-v1_" + [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    $mac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($hmacKey))
    try { $hash = ([BitConverter]::ToString($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes("api-key:v1:$secret"))) -replace '-', '').ToLowerInvariant() } finally { $mac.Dispose() }
    $id = [guid]::NewGuid().ToString(); $prefix=$secret.Substring(0,16); $suffix=$secret.Substring($secret.Length-8)
    Sql @"
insert into api_keys (id,user_id,name,key_prefix,key_suffix,key_hash,key_hash_version,status,default_group_id,service_group_id,allowed_model_ids,allowed_group_ids,ip_allowlist,rpm_limit,tpm_limit,concurrency_limit,credit_limit,expires_at)
values ('$id'::uuid,'$userId'::uuid,'Codex all capabilities test','$prefix','$suffix',decode('$hash','hex'),1,'active','$groupId'::uuid,'$groupId'::uuid,'[]'::jsonb,'["$groupId"]'::jsonb,'[]'::jsonb,120,100000,4,1000,now()+interval '20 minutes');
"@ | Out-Null
    @{ id=$id; secret=$secret }
}
function Call([string]$name,[string]$method,[string]$path,[object]$body=$null,[hashtable]$form=$null,[switch]$binary) {
    $rid = "req_all_caps_$([guid]::NewGuid().ToString('N'))"; $uri="$EndpointRoot$path"; $status=0; $text=""; $temp=$null
    try {
        $headers=@{ Authorization="Bearer $($script:key.secret)"; 'X-Request-Id'=$rid; 'X-Nexus-Group'=$script:groupCode }
        if ($binary) {
            # Keep the response as a byte stream so binary audio is not decoded as text.
            $r=Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 20 -Compress) -TimeoutSec 240 -SkipHttpErrorCheck
            $status=[int]$r.StatusCode
            $temp=[IO.Path]::Combine([IO.Path]::GetTempPath(), "nexus-audio-$([guid]::NewGuid().ToString('N')).wav")
            $stream=[IO.File]::Open($temp,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::None)
            try { $r.RawContentStream.CopyTo($stream) } finally { $stream.Dispose() }
            $text="bytes=$((Get-Item $temp).Length)"
        }
        elseif ($form) { $r=Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -Form $form -TimeoutSec 240 -SkipHttpErrorCheck; $status=[int]$r.StatusCode; $text=[string]$r.Content }
        elseif ($body) { $r=Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 20 -Compress) -TimeoutSec 240 -SkipHttpErrorCheck; $status=[int]$r.StatusCode; $text=[string]$r.Content }
        else { $r=Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -TimeoutSec 240 -SkipHttpErrorCheck; $status=[int]$r.StatusCode; $text=[string]$r.Content }
    } catch { $text=$_.Exception.Message }
    if ($text.Length -gt 700) { $text=$text.Substring(0,700)+"..." }
    Log "HTTP name=$name request_id=$rid status=$status $text"
    $script:requestIds += $rid
    if ($temp) { $script:lastBinaryPath=$temp }
    [pscustomobject]@{ Name=$name; RequestId=$rid; Status=$status; Body=$text }
}

$script:requestIds=@(); $script:lastBinaryPath=$null; $script:key=$null; $script:groupCode='caicai_fad3fbf70af343ad8a9b592d0f6761ed'
$keyId=$null; $results=@(); $videoTaskId=$null
try {
    $row=Sql "select concat_ws('|',g.id,u.id) from routing_groups g join lateral (select candidate.id from users candidate join wallet_accounts w on w.user_id=candidate.id where candidate.status='active' and w.permanent_credits+w.expiring_credits>100 order by w.permanent_credits+w.expiring_credits desc limit 1) u on true where g.code='$($script:groupCode)' and g.status='active';"
    $groupId,$userId=$row.Split('|'); $script:key=New-Key $groupId $userId; $keyId=$script:key.id
    $results += Call 'models' GET '/v1/models'
    $results += Call 'chat' POST '/v1/chat/completions' @{model='gpt-5.6-sol';messages=@(@{role='user';content='只回复 OK'});stream=$false;max_tokens=8}
    $results += Call 'responses' POST '/v1/responses' @{model='gpt-5.6-sol';input='只回复 OK';stream=$false;max_output_tokens=8}
    $results += Call 'image_generations' POST '/v1/images/generations' $null @{model='gpt-image-2';prompt='a single blue circle on white background';n='1';quality='low';aspect_ratio='1:1';resolution='1k'}
    $results += Call 'image_tasks' POST '/v1/images/tasks' @{model='gpt-image-2';prompt='a single blue circle on white background'}
    $results += Call 'embeddings' POST '/v1/embeddings' @{model='text-embedding-3-small';input='hello'}
    $speech=Call 'speech' POST '/v1/audio/speech' @{model='tts-1';input='测试';voice='alloy';response_format='wav'} -binary
    if ($script:lastBinaryPath -and (Get-Item $script:lastBinaryPath).Length -gt 0) {
        $results += $speech
        $results += Call 'transcription' POST '/v1/audio/transcriptions' $null @{model='whisper-1';file=Get-Item $script:lastBinaryPath}
    } else { $results += $speech }
    $video=Call 'video_create' POST '/v1/videos' @{model='grok-imagine-video';prompt='a blue ball rolling on a white floor';duration=5;resolution='480p';aspect_ratio='1:1';generate_audio=$false}
    $results += $video
    try { $j=$video.Body|ConvertFrom-Json; foreach($n in @('id','task_id','taskId')){if($j.$n){$videoTaskId=[string]$j.$n;break}}; if(-not $videoTaskId -and $j.data){foreach($n in @('id','task_id','taskId')){if($j.data.$n){$videoTaskId=[string]$j.data.$n;break}}} } catch {}
    $videoList = Call 'video_list' GET '/v1/videos?model=grok-imagine-video&limit=1'
    $results += $videoList
    # If creation failed, reuse a task id returned by the list endpoint so the
    # detail route is still exercised and not silently skipped.
    if (-not $videoTaskId) {
        try {
            $listJson = $videoList.Body | ConvertFrom-Json
            if ($listJson.data -and $listJson.data.Count -gt 0 -and $listJson.data[0].id) {
                $videoTaskId = [string]$listJson.data[0].id
            }
        } catch {}
    }
    if (-not $videoTaskId) { $videoTaskId = 'validation-probe-task' }
    $results += Call 'video_detail' GET ("/v1/videos/"+[Uri]::EscapeDataString($videoTaskId)+"?model=grok-imagine-video")
    $ok=@($results|Where-Object{$_.Status -ge 200 -and $_.Status -lt 300}).Count; $bad=$results.Count-$ok; Log "SUMMARY total=$($results.Count) passed=$ok failed=$bad video_task=$videoTaskId"
    $ids=($script:requestIds|ForEach-Object{"'$_'"}) -join ','
    $rows=Sql "select l.request_id,l.status_code,coalesce(s.code,''),l.streaming,coalesce(l.platform_error_code,'') from request_logs l left join suppliers s on s.id=l.supplier_id where l.request_id in ($ids) order by l.created_at;"
    foreach($line in ($rows -split "`n")){if($line){Log "LOG $line"}}
} catch { Log "FATAL $($_.Exception.Message)" }
finally {
    if($keyId){try{Sql "update api_keys set status='revoked',revoked_at=now(),status_changed_at=now(),version=version+1 where id='$keyId'::uuid;"|Out-Null;Log 'CLEANUP temporary key revoked'}catch{Log 'CLEANUP_FAIL'}}
    if($script:lastBinaryPath -and (Test-Path $script:lastBinaryPath)){Remove-Item -LiteralPath $script:lastBinaryPath -Force}
    $path=Join-Path $logDir "all-capabilities-production-$((Get-Date).ToString('yyyyMMdd-HHmmss')).log"; $log|Set-Content -LiteralPath $path -Encoding utf8NoBOM; $log|ForEach-Object{Write-Output $_}
}
