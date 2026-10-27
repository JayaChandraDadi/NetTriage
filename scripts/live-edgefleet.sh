#!/usr/bin/env bash
# The full loop against a real EdgeFleet controller and simulated routers:
#   EdgeFleet rolls an MTU change out to a pilot group  ->  NetTriage learns the change
#   from EdgeFleet's event log  ->  telemetry shows the pilot routers' internet tunnels
#   degrading after each applies it  ->  NetTriage blames the rollout  ->  a person
#   approves the proposed rollback in NetTriage  ->  EdgeFleet rolls it back.
# Needs the EdgeFleet repo next to this one (or EDGEFLEET_HOME) built with mvn install.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
EF=${EDGEFLEET_HOME:-$ROOT/../edgefleet}
RUN=${RUN_DIR:-$ROOT/.live}
N=${DEVICES:-40}
EAPI=http://localhost:8090/api
NAPI=http://localhost:8095/api
rm -rf "$RUN"; mkdir -p "$RUN/bin"; cd "$RUN"
cp "$EF/controller/target/controller-0.1.0.jar" "$EF/device-sim/target/device-sim-0.1.0-exec.jar" \
   "$ROOT/service/target/service-0.1.0.jar" bin/
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
wait_for() { local t=$1; shift; for _ in $(seq 1 "$t"); do "$@" && return 0; sleep 0.5; done; echo "timed out: $*" >&2; return 1; }
cleanup() { for p in $(cat pids 2>/dev/null); do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

PKI_DIR="$RUN/pki" EMBEDDED_PG_DIR="$RUN/pgdata" nohup java -Xmx768m -jar bin/controller-0.1.0.jar > edgefleet.log 2>&1 &
echo $! >> pids
wait_for 240 curl -sf http://localhost:8090/actuator/health -o /dev/null
echo "EdgeFleet up"

python3 - "$EF/examples/branch-template.json" > t1.json <<'PY'
import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))
PY
python3 - "$EF/examples/branch-template.json" > t2.json <<'PY'
import json,sys; t=json.load(open(sys.argv[1])); t["comment"]="internet MTU 1400 for the new IPsec profile"
t["body"]["transports"][1]["mtu"]=1400; print(json.dumps(t))
PY
v1=$(curl -s -X POST "$EAPI/templates" -H 'Content-Type: application/json' --data @t1.json | json 'd["version"]')
for g in branches pilot; do
  curl -s -X PUT "$EAPI/groups/$g/baseline" -H 'Content-Type: application/json' -d "{\"template\":\"branch\",\"version\":$v1}" >/dev/null
done
python3 "$EF/tools/make_inventory.py" --devices "$N" | python3 -c '
import json,sys; d=json.load(sys.stdin)
for i,x in enumerate(d):
    if i % 4 == 1: x["group"]="pilot"
print(json.dumps(d))' > inventory.json
curl -s -X POST "$EAPI/inventory" -H 'Content-Type: application/json' --data @inventory.json > tokens.json
curl -s "$EAPI/pki/ca.crt" > ca.crt
nohup java -Xmx512m -jar bin/device-sim-0.1.0-exec.jar --ca ca.crt --tokens tokens.json --duration-sec 3600 \
  --heartbeat-ms 2000 > sim.log 2>&1 &
echo $! >> pids
insync() { [ "$(curl -s "$EAPI/fleet/summary" | json 'd["onboarded"]["inSync"]')" = "$N" ]; }
wait_for 240 insync
echo "$N routers onboarded and in sync"

EDGEFLEET_URL=http://localhost:8090 nohup java -Xmx512m -jar bin/service-0.1.0.jar > nettriage.log 2>&1 &
echo $! >> pids
wait_for 120 curl -sf http://localhost:8095/actuator/health -o /dev/null
echo "NetTriage up, following EdgeFleet"

v2=$(curl -s -X POST "$EAPI/templates" -H 'Content-Type: application/json' --data @t2.json | json 'd["version"]')
dep=$(curl -s -X POST "$EAPI/deployments" -H 'Content-Type: application/json' \
  -d "{\"template\":\"branch\",\"version\":$v2,\"group\":\"pilot\",\"batchPercents\":[30,100],\"maxFailurePct\":5,\"healthTimeoutSec\":30}" \
  | json 'd["id"]')
done_dep() { [ "$(curl -s "$EAPI/deployments/$dep" | json 'd["deployment"]["state"]')" = "COMPLETED" ]; }
wait_for 240 done_dep
echo "rollout $dep completed on the pilot group (its health checks passed)"
sleep 5
curl -s "$NAPI/changes" | json 'json.dumps([{k: c[k] for k in ("element","label","color","routers","diff")} for c in d], indent=1)'

python3 "$ROOT/tools/replay_telemetry.py" --deployment "$dep" --color biz-internet --loss 5
curl -s "$NAPI/incidents?all=false" > incidents.json
python3 - <<'PY'
import json; inc=json.load(open("incidents.json"))
for i in inc:
    print(f'{i["id"]} {i["rootCause"]} {i["confidence"]} margin={i["margin"]}\n  {i["headline"]}')
    for e in i["evidence"]: print(f'  {e["id"]}: {e["text"]}')
    for a in i["actions"]: print(f'  {a["id"]} [{a["kind"]}] {a["title"]}')
PY
inc=$(json 'd[0]["id"]' < incidents.json)
echo "approving rollback A1 of $inc as 'jaya'"
curl -s -X POST "$NAPI/incidents/$inc/actions/A1/approve" -H 'Content-Type: application/json' -d '{"approver":"jaya"}' \
  | json 'json.dumps({k: d[k] for k in ("id","state","decidedBy","outcome")})'
rolled() { [ "$(curl -s "$EAPI/deployments/$dep" | json 'd["deployment"]["state"]')" = "ROLLED_BACK" ]; }
wait_for 240 rolled
echo "EdgeFleet deployment $dep: ROLLED_BACK"
curl -s "$EAPI/devices" | json 'json.dumps(sorted(set((x["group"], x["intendedVersion"]) for x in d)))'
python3 - "$dep" <<'PY' > "$RUN/live-edgefleet.json"
import json,sys,urllib.request
g=lambda u: json.load(urllib.request.urlopen(u))
print(json.dumps({"deployment": sys.argv[1],
  "changesSeenByNetTriage": g("http://localhost:8095/api/changes"),
  "incidents": g("http://localhost:8095/api/incidents?all=true"),
  "actions": g("http://localhost:8095/api/actions"),
  "edgefleetDeployment": g(f"http://localhost:8090/api/deployments/{sys.argv[1]}")["deployment"]}, indent=1))
PY
echo "wrote $RUN/live-edgefleet.json"
