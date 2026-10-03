set -eu
sha256sum /opt/nexusCenter/backend/src/main/java/com/nexusapi/server/modules/gateway/upstream/OpenAiUpstreamClient.java
docker cp nexus-center-prod-app-1:/app/app.jar /opt/nexusCenter/backend/incident-heartbeat-original-20260918.jar
ls -l /opt/nexusCenter/backend/incident-heartbeat-original-20260918.jar
docker image inspect nexus-center-prod-app --format '{{.Id}}'
