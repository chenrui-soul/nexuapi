docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -At <<'SQL'
SELECT request_id,status_code,platform_error_code,upstream_error_summary,input_tokens,output_tokens,request_payload_size,duration_ms FROM request_logs WHERE created_at>now()-interval '5 minutes' AND status_code>=400 ORDER BY created_at DESC LIMIT 10;
SQL
docker logs --since 5m nexus-center-prod-app-1 2>&1 | grep -E 'WARN|ERROR|NullPointer|reset|timed out' | tail -12
ls /opt/nexusCenter/backend | head
command -v mvn || true
docker images --format '{{.Repository}}:{{.Tag}}' | grep -E 'maven|temurin'
