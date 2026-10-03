set -eu
date -Is
free -m
vmstat 1 3
docker inspect nexus-center-prod-app-1 --format 'health={{.State.Health.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}} memory_limit={{.HostConfig.Memory}}'
docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -At <<'SQL'
SET statement_timeout='8s';
SELECT 'TEST_KEYS',status,count(*) FROM api_keys WHERE name='Incident SSE regression 20260918' GROUP BY status;
SELECT 'TEST_RESERVATIONS',status,count(*) FROM wallet_reservations WHERE request_id LIKE 'req_incident_%' GROUP BY status;
SELECT 'REQUESTS',status_code,coalesce(platform_error_code,''),count(*) FROM request_logs WHERE request_id LIKE 'req_incident_%' GROUP BY 2,3;
SELECT 'FINAL_CASES',request_id,status_code,coalesce(platform_error_code,''),duration_ms,retry_count,request_payload_size FROM request_logs WHERE request_id IN ('req_incident_b3989d5907884685ac5d363356ef6871','req_incident_0e7b7192e2974f1487466f9b0989cde9','req_incident_76c20fdd262e42bdbc5b792b1a0b922e','req_incident_cbbfa95b238b42d1aabfd382cb3cbcfe');
SELECT 'CHANNEL',concurrency_limit,timeout_ms FROM channels WHERE id='1c50150e-7322-402f-94a1-5fdc5c0fbcf6';
SQL
python3 - <<'PY'
import subprocess,json
env=json.loads(subprocess.check_output(['docker','inspect','nexus-center-prod-app-1']).decode())[0]['Config']['Env']
password=next(v.split('=',1)[1] for v in env if v.startswith('REDIS_PASSWORD='))
for command in [('GET','nexus:gateway:channel:1c50150e-7322-402f-94a1-5fdc5c0fbcf6:concurrency'),('TTL','nexus:gateway:channel:1c50150e-7322-402f-94a1-5fdc5c0fbcf6:concurrency')]:
 p=subprocess.run(['docker','exec','-e','REDISCLI_AUTH='+password,'nexus-center-prod-redis-1','redis-cli','--raw']+list(command),stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
 print('CHANNEL_LEASE',command[0],p.stdout.strip())
for v in env:
 if v.split('=')[0] in ['JAVA_TOOL_OPTIONS','DB_POOL_MAX_SIZE','DB_POOL_MIN_IDLE']:print(v)
PY
curl -sS --max-time 8 -o /dev/null -w 'READINESS %{http_code} %{time_total}\n' https://nexusapi.center/actuator/health/readiness
