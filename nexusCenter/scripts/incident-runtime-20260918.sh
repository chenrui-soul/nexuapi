python3 - <<'PY'
import subprocess,json
x=json.loads(subprocess.check_output(['docker','inspect','nexus-center-prod-app-1']).decode())[0]
print('runtime',json.dumps({'entrypoint':x['Config']['Entrypoint'],'cmd':x['Config']['Cmd'],'memory_limit':x['HostConfig']['Memory']}))
for v in x['Config']['Env']:
 if v.split('=')[0] in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','DB_POOL_MAX_SIZE','DB_POOL_MIN_IDLE','UPSTREAM_MAX_ATTEMPTS','UPSTREAM_MAX_CONNECTIONS','GATEWAY_TEXT_QUEUE_MAX_WAIT']:print(v)
print('host_mem',subprocess.check_output(['free','-m']).decode())
PY
sed -n '45,95p' /opt/nexusCenter/compose.prod.yaml
cat /proc/sys/vm/swappiness
iostat -dx 1 3 | tail -15
docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -At <<'SQL'
SELECT rgs.status,s.code FROM routing_group_suppliers rgs JOIN suppliers s ON s.id=rgs.supplier_id WHERE rgs.group_id='27000d13-e4b9-477d-b8cd-605e982a4246';
SQL
