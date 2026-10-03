set -eu
cd /opt/nexusCenter
expected=sha256:84f29d3ae87aae7efaa35f683d33e1bd25e26a917d5d43b4b050f3ab8366f3fa
actual=$(docker inspect nexus-center-prod-app-1 --format '{{.Image}}')
if [ "$actual" != "$expected" ]; then echo 'Production image changed; deployment stopped'; exit 1; fi
docker tag "$expected" nexus-center-prod-app:before-heartbeat-20260918
docker build --network=none -t nexus-center-prod-app:heartbeat-20260918 /opt/nexusCenter/incident-heartbeat-20260918
docker tag nexus-center-prod-app:heartbeat-20260918 nexus-center-prod-app:latest
docker compose -f compose.prod.yaml up -d --no-deps --no-build --force-recreate app
for attempt in $(seq 1 45); do
  status=$(docker inspect nexus-center-prod-app-1 --format '{{.State.Health.Status}}')
  if [ "$status" = healthy ]; then
    docker exec nexus-center-prod-nginx-1 nginx -t
    docker exec nexus-center-prod-nginx-1 nginx -s reload
    source=backend/src/main/java/com/nexusapi/server/modules/gateway/upstream/OpenAiUpstreamClient.java
    cp -p "$source" "$source.before-heartbeat-20260918"
    cp incident-heartbeat-20260918/OpenAiUpstreamClient.java "$source"
    docker inspect nexus-center-prod-app-1 --format 'DEPLOYED image={{.Image}} health={{.State.Health.Status}}'
    exit 0
  fi
  sleep 3
done
docker tag nexus-center-prod-app:before-heartbeat-20260918 nexus-center-prod-app:latest
docker compose -f compose.prod.yaml up -d --no-deps --no-build --force-recreate app
echo 'READINESS FAILED: original image restored'
exit 1
