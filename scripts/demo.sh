#!/usr/bin/env bash
# NetTriage on its own, no Kafka or EdgeFleet needed: starts the service, streams sixteen
# minutes of a simulated 200-site fleet into it (a provider brownout and an LTE outage
# that resolve, a bad MTU rollout and a hub circuit problem still open), and files a
# rollback request over MCP the way an AI agent would. Then: cd dashboard && npm run dev
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${PORT:-8095}
URL=http://localhost:$PORT
if ! curl -sf "$URL/actuator/health" -o /dev/null; then
  PORT=$PORT nohup java -Xmx768m -jar "$ROOT/service/target/service-0.1.0.jar" > "$ROOT/demo-service.log" 2>&1 &
  for _ in $(seq 1 60); do curl -sf "$URL/actuator/health" -o /dev/null && break; sleep 1; done
  echo "NetTriage started on $URL (log: demo-service.log)"
fi
java -cp "$ROOT/bench/target/bench-0.1.0-exec.jar" -Dloader.main=io.nettriage.bench.DemoFeed \
  org.springframework.boot.loader.launch.PropertiesLauncher "$URL"

# an MCP client asks for the rollback; a person still has to approve it
H=(-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream")
SID=$(curl -s -D - -o /dev/null "${H[@]}" -X POST "$URL/mcp" -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
  "params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"demo"}}}' \
  | grep -i '^mcp-session-id' | awk '{print $2}' | tr -d '\r')
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" -X POST "$URL/mcp" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' -o /dev/null
INC=$(curl -s "$URL/api/incidents?all=false" | python3 -c "import json,sys; print([i['id'] for i in json.load(sys.stdin) if i['rootCause'].startswith('change/')][0])")
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" -X POST "$URL/mcp" -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",
  \"params\":{\"name\":\"request_remediation\",\"arguments\":{\"incident_id\":\"$INC\",\"action_id\":\"A1\",
  \"justification\":\"E1-E3: only routers on branch v4 degraded, each after it applied the change; E5 shows the MTU drop. Roll back before the next batch.\"}}}" -o /dev/null
curl -s "$URL/api/incidents?all=true" | python3 -c "
import json,sys
for i in json.load(sys.stdin): print(f'{i[\"id\"]} {i[\"state\"]:<8} {i[\"confidence\"]:<6} {i[\"headline\"]}')"
echo "rollback request filed over MCP for $INC; approve it in the dashboard (cd dashboard && npm run dev)"
