date -Is
docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -At <<'SQL'
SET statement_timeout='5s';
SELECT request_id,attempt_no,outcome,error_category,error_summary,duration_ms FROM upstream_attempt_logs WHERE request_id LIKE 'req_incident_%' AND created_at>now()-interval '8 minutes' ORDER BY created_at DESC LIMIT 12;
SQL
ss -tin dst 149.88.91.176 | head -25
docker logs --since 3m nexus-center-prod-app-1 2>&1 | grep -E 'timed out|reset|ERROR|WARN' | tail -12
