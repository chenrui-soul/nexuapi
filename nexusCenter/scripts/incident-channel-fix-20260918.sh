set -eu
backup=/opt/nexusCenter/incident-channel-20260918.json
if [ ! -e "$backup" ]; then
  umask 077
  docker exec -i nexus-center-prod-postgres-1 psql -X -qAt -U nexus -d nexus_api > "$backup" <<'SQL'
SELECT json_build_object('id',id,'name',name,'concurrency_limit',concurrency_limit,'version',version,'updated_at',updated_at) FROM channels WHERE id='1c50150e-7322-402f-94a1-5fdc5c0fbcf6';
SQL
fi
docker exec -i nexus-center-prod-postgres-1 psql -X -U nexus -d nexus_api -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;
UPDATE channels SET concurrency_limit=4,version=version+1,updated_at=now() WHERE id='1c50150e-7322-402f-94a1-5fdc5c0fbcf6' AND concurrency_limit=2;
SELECT id,name,concurrency_limit,timeout_ms FROM channels WHERE id='1c50150e-7322-402f-94a1-5fdc5c0fbcf6';
COMMIT;
SQL
