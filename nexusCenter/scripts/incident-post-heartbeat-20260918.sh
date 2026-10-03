date -Is
docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -At <<'SQL'
SELECT request_id,public_model,status_code,platform_error_code,upstream_error_summary,duration_ms,input_tokens,output_tokens FROM request_logs WHERE created_at>'2026-09-18 13:28:35+00' ORDER BY created_at DESC LIMIT 20;
SQL
docker logs --since 2026-09-18T13:28:35Z nexus-center-prod-app-1 2>&1 | grep -E 'ERROR|WARN|timed out' | tail -12
free -m
