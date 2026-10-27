#!/usr/bin/env bash
# Live run against the real WanPulse pipeline: the edge simulator streams tunnel probes
# over gRPC, WanPulse windows them in Kafka Streams and publishes tunnel.health, and
# NetTriage consumes that topic. Six scripted faults; the result is scored against the
# simulator's ground-truth log. Needs the WanPulse repo next to this one (or
# WANPULSE_HOME), built with mvn install. Takes about 17 minutes.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WP=${WANPULSE_HOME:-$ROOT/../wanpulse}
RUN=${RUN_DIR:-$ROOT/.live-wanpulse}
EDGES=${EDGES:-500}
DURATION=${DURATION_SEC:-960}
rm -rf "$RUN"; mkdir -p "$RUN"
"$WP/scripts/run-local.sh" "$RUN/wanpulse" > "$RUN/wanpulse-start.log"
BOOT=$(cat "$RUN/wanpulse/bootstrap")
cleanup() { [ -f "$RUN/nettriage.pid" ] && kill "$(cat "$RUN/nettriage.pid")" 2>/dev/null || true; "$WP/scripts/stop-local.sh" "$RUN/wanpulse" >/dev/null 2>&1 || true; }
trap cleanup EXIT

cp "$ROOT/service/target/service-0.1.0.jar" "$RUN/"
KAFKA_ENABLED=true KAFKA_BOOTSTRAP="$BOOT" REPLAY_ENABLED=false nohup java -Xmx768m -jar "$RUN/service-0.1.0.jar" \
  > "$RUN/nettriage.log" 2>&1 &
echo $! > "$RUN/nettriage.pid"
for _ in $(seq 1 120); do curl -sf http://localhost:8095/actuator/health -o /dev/null && break; sleep 1; done
echo "WanPulse at $BOOT, NetTriage consuming tunnel.health"

python3 "$ROOT/tools/watch_incidents.py" http://localhost:8095 "$RUN/watch.jsonl" $((DURATION + 90)) &
WATCH=$!
(cd "$RUN" && java -jar "$RUN/wanpulse/bin/edge-simulator-0.1.0.jar" --edges "$EDGES" --duration-sec "$DURATION" \
   --scenario "$ROOT/scenarios/live-faults.json" --truth-log "$RUN/fault-truth.jsonl" > "$RUN/simulator.log" 2>&1)
echo "simulator finished; watching for incidents to resolve"
wait $WATCH
curl -s http://localhost:8095/api/status > "$RUN/status.json"
curl -s "http://localhost:8095/api/incidents?all=true" > "$RUN/incidents.json"
python3 "$ROOT/tools/score_live.py" --scenario "$ROOT/scenarios/live-faults.json" --truth "$RUN/fault-truth.jsonl" \
  --watch "$RUN/watch.jsonl" --bootstrap "$BOOT" --out "$RUN/live-wanpulse.json"
