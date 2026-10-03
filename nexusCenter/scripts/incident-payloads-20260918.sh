docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -At <<'SQL'
SET statement_timeout='8s';
SELECT started_at,status_code,public_model,request_payload_size,duration_ms,retry_count,request_id FROM request_logs WHERE created_at > '2026-09-18 11:30:00+00' ORDER BY created_at DESC LIMIT 40;
SELECT jsonb_typeof(request_detail),octet_length(request_detail::text),jsonb_object_keys(request_detail) FROM request_logs WHERE request_id='req_fcf6a6d6499f497c9c0b100f4ae1995a';
SQL
docker exec nexus-center-prod-app-1 sh -c 'ls /opt/java/openjdk/bin/jcmd /opt/java/openjdk/bin/jstat 2>/dev/null'
command -v iostat || true
cat /sys/fs/cgroup/memory/memory.stat 2>/dev/null | head -15
df -h /
