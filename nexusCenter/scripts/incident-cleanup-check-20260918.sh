python3 - <<'PY'
import subprocess,json
env=json.loads(subprocess.check_output(['docker','inspect','nexus-center-prod-app-1']).decode())[0]['Config']['Env']
password=next(v.split('=',1)[1] for v in env if v.startswith('REDIS_PASSWORD='))
def redis(*args):
 return subprocess.check_output(['docker','exec','-e','REDISCLI_AUTH='+password,'nexus-center-prod-redis-1','redis-cli','--raw']+list(args)).decode().strip()
sql="SELECT id FROM api_keys WHERE name='Incident SSE regression 20260918';"
p=subprocess.run(['docker','exec','-i','nexus-center-prod-postgres-1','psql','-X','-qAt','-U','nexus','-d','nexus_api'],input=sql,stdout=subprocess.PIPE,universal_newlines=True)
for key in p.stdout.splitlines():
 print('TEST_KEY_LEASE',key,redis('GET','nexus:gateway:key:api-key:'+key+':concurrency') or 'NONE')
print('CHANNEL_LEASE',redis('GET','nexus:gateway:channel:1c50150e-7322-402f-94a1-5fdc5c0fbcf6:concurrency') or 'NONE')
PY
docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -At <<'SQL'
SELECT request_id,public_model,status_code,duration_ms,created_at FROM request_logs WHERE created_at>now()-interval '4 minutes' ORDER BY created_at DESC LIMIT 12;
SELECT request_id,status,created_at FROM wallet_reservations WHERE status='reserved' ORDER BY created_at DESC LIMIT 10;
SQL
