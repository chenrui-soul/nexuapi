date -Is
free -m
vmstat 1 4
ps -eo pid,stat,comm,rss,wchan:32 --sort=-rss | head -18
docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -At <<'SQL'
SET statement_timeout='8s';
SELECT table_name,string_agg(column_name,',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('channels','request_logs','routing_groups','routing_group_supplier_credentials') GROUP BY table_name;
SELECT r.request_id,r.streaming,g.code,c.id,c.name,c.base_url,c.timeout_ms,c.concurrency_limit FROM request_logs r LEFT JOIN channels c ON c.id=r.channel_id LEFT JOIN routing_groups g ON g.id=r.group_id WHERE r.request_id IN ('req_fcf6a6d6499f497c9c0b100f4ae1995a','req_a8492929822a4e15854d4fe2bdd0ea9c');
SELECT state,wait_event_type,wait_event,count(*) FROM pg_stat_activity GROUP BY 1,2,3;
SQL
python3 - <<'PY'
import subprocess,json
r=subprocess.run(['docker','logs','--since','2026-09-18T12:05:00Z','--until','2026-09-18T12:25:00Z','nexus-center-prod-app-1'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True,timeout=15)
for line in (r.stdout+'\n'+r.stderr).splitlines():
 try:
  x=json.loads(line)
  if x.get('level') in ['ERROR','WARN']:
   print(json.dumps({'time':x.get('@timestamp'),'message':x.get('message'),'causes':[l for l in x.get('stack_trace','').splitlines() if l.startswith('Caused by:')][:4]}))
 except ValueError: pass
PY
