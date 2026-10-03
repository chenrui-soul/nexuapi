set -eu
cd /opt/nexusCenter
python3 - <<'PY'
from pathlib import Path
import shutil
p=Path('compose.prod.yaml')
backup=p.with_name('compose.prod.yaml.codex-20260918.bak')
text=p.read_text()
before,app=text.split('\n  app:\n',1)
app,after=app.split('\n  frontend:\n',1)
if 'APP_MEMORY_LIMIT' not in app:
 assert 'JAVA_TOOL_OPTIONS:' not in app and 'mem_limit:' not in app
 if not backup.exists():shutil.copy2(str(p),str(backup))
 app=app.replace('    restart: unless-stopped\n','    restart: unless-stopped\n    # Shared host: reserve memory for PostgreSQL, Docker, and file cache.\n    mem_limit: ${APP_MEMORY_LIMIT:-1536m}\n',1)
 app=app.replace('    environment:\n','    environment:\n      JAVA_TOOL_OPTIONS: ${APP_JAVA_TOOL_OPTIONS:--Xms256m -Xmx768m -XX:MaxDirectMemorySize=192m}\n      DB_POOL_MAX_SIZE: ${DB_POOL_MAX_SIZE:-16}\n      DB_POOL_MIN_IDLE: ${DB_POOL_MIN_IDLE:-4}\n',1)
 p.write_text(before+'\n  app:\n'+app+'\n  frontend:\n'+after)
 print('compose backed up and app memory settings written')
PY
if ! docker compose -f compose.prod.yaml config --quiet; then
  cp -p compose.prod.yaml.codex-20260918.bak compose.prod.yaml
  exit 1
fi
docker compose -f compose.prod.yaml up -d --no-deps --no-build app
for attempt in $(seq 1 45); do
  status=$(docker inspect nexus-center-prod-app-1 --format '{{.State.Health.Status}}')
  if [ "$status" = healthy ]; then
    docker exec nexus-center-prod-nginx-1 nginx -t
    docker exec nexus-center-prod-nginx-1 nginx -s reload
    docker inspect nexus-center-prod-app-1 --format 'healthy memory_limit={{.HostConfig.Memory}} restarts={{.RestartCount}}'
    free -m
    exit 0
  fi
  sleep 3
done
cp -p compose.prod.yaml.codex-20260918.bak compose.prod.yaml
docker compose -f compose.prod.yaml up -d --no-deps --no-build app
echo 'READINESS FAILED: rolled back app configuration'
exit 1
