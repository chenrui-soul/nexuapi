docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -At <<'SQL'
SET statement_timeout='8s';
SELECT json_build_object('group',g.code,'group_id',g.id,'user_id',r.user_id,'channel',c.name,'limit',c.concurrency_limit,'channel_metadata',c.metadata,'request_bytes',r.request_payload_size,'request_summary',r.request_summary,'supplier',s.code) FROM request_logs r JOIN routing_groups g ON g.id=r.group_id JOIN channels c ON c.id=r.channel_id JOIN suppliers s ON s.id=r.supplier_id WHERE r.request_id='req_fcf6a6d6499f497c9c0b100f4ae1995a';
SELECT id,public_name FROM ai_models WHERE public_name in ('gpt-6-astra','gpt-5.6-sol');
SELECT table_name,string_agg(column_name,',') FROM information_schema.columns WHERE table_schema='public' AND table_name in ('wallet_accounts','api_keys') GROUP BY table_name;
SELECT s.code,c.name,c.operation_code,c.status,c.concurrency_limit,c.timeout_ms FROM channels c JOIN suppliers s ON s.id=c.supplier_id WHERE s.code='hzz';
SQL
cat /etc/fstab | tail -4
ls /opt/nexusCenter/backend/target/*.jar
docker exec nexus-center-prod-app-1 java -version
vmstat 1 3
