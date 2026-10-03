$ErrorActionPreference = 'Stop'
$server = '8.218.238.141'
$sshKey = "$env:USERPROFILE\.ssh\nexus-deploy-ed25519"
function Invoke-Sql([string]$sql) {
  $sql | ssh -o StrictHostKeyChecking=no -i $sshKey root@$server "docker exec -i nexus-center-prod-postgres-1 psql -U nexus -d nexus_api -At -F '|' -v ON_ERROR_STOP=1"
}
$ids = (Invoke-Sql "select g.id,m.id,u.id from routing_groups g join ai_models m on m.public_name='gpt-5.6-sol' and m.status='active' join lateral (select u.id from users u join wallet_accounts w on w.user_id=u.id where u.status='active' and w.permanent_credits+w.expiring_credits>100 order by w.permanent_credits+w.expiring_credits desc limit 1) u on true where g.code='gpt_tj' and g.status='active';").Trim().Split('|')
$groupId,$modelId,$userId = $ids
$apiKey = 'sk-nx-v1_' + [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 })).TrimEnd('=').Replace('+','-').Replace('/','_')
$hmacKey = (& ssh -o StrictHostKeyChecking=no -i $sshKey root@$server "docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1").Trim()
$hmac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($hmacKey))
$hash = ([BitConverter]::ToString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("api-key:v1:$apiKey"))) -replace '-','').ToLowerInvariant()
$id = [Guid]::NewGuid().ToString()
$prefix = $apiKey.Substring(0,16); $suffix = $apiKey.Substring($apiKey.Length-8)
$sql = @"
insert into api_keys (id,user_id,name,key_prefix,key_suffix,key_hash,key_hash_version,status,default_group_id,service_group_id,allowed_model_ids,allowed_group_ids,ip_allowlist,rpm_limit,tpm_limit,concurrency_limit,credit_limit,expires_at)
values ('$id','$userId','temporary ycyapi test','$prefix','$suffix',decode('$hash','hex'),1,'active','$groupId','$groupId','["$modelId"]'::jsonb,'["$groupId"]'::jsonb,'[]'::jsonb,5,100000,1,1,now()+interval '5 minutes');
"@
try {
  Invoke-Sql $sql | Out-Null
  $body = '{"model":"gpt-5.6-sol","input":"只回复：YCY文本接口测试成功"}'
  $response = curl.exe -sS -i -H "Authorization: Bearer $apiKey" -H 'Content-Type: application/json' --data $body https://nexusapi.center/v1/responses
  $response
} finally {
  Invoke-Sql "update api_keys set status='revoked',revoked_at=now(),status_changed_at=now(),version=version+1 where id='$id';" | Out-Null
}
