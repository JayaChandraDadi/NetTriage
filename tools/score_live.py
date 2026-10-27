#!/usr/bin/env python3
"""Scores a live run: WanPulse's fault truth log against NetTriage's incidents.

For each scripted fault: was the right root cause named, how long after the fault
really started (wall clock, including WanPulse's windowing and Kafka), how long after
it ended the incident resolved, and how many per-tunnel SLA alerts WanPulse raised
for it. Incidents whose root cause matches no active fault count as false.

usage: score_live.py --scenario s.json --truth fault-truth.jsonl --watch watch.jsonl
                     [--bootstrap host:port] [--out live-wanpulse.json]
"""
import argparse, json, statistics
from collections import defaultdict

def expected(m):
    if m.get("siteId") is not None:
        return f'site/{m["siteId"]}/{m["color"]}' if m.get("color") else f'site/{m["siteId"]}'
    if m.get("hub"):
        return f'hub/{m["hub"]}/{m["color"]}' if m.get("color") else f'hub/{m["hub"]}'
    if m.get("region"):
        return f'provider/{m["color"]}/{m["region"]}'
    return f'carrier/{m["color"]}'

def count_alerts(bootstrap, faults):
    from confluent_kafka import Consumer, TopicPartition
    import uuid
    c = Consumer({"bootstrap.servers": bootstrap, "group.id": "score-" + uuid.uuid4().hex,
                  "auto.offset.reset": "earliest", "enable.auto.commit": False, "isolation.level": "read_committed"})
    c.subscribe(["sla.events"])
    opened, idle = [], 0
    while idle < 5:
        msgs = c.consume(500, timeout=1.0)
        if not msgs:
            idle += 1
            continue
        idle = 0
        for m in msgs:
            if m.error():
                continue
            e = json.loads(m.value())
            if e.get("type") == "OPENED":
                opened.append(e)
    c.close()
    return opened

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", required=True)
    ap.add_argument("--truth", required=True)
    ap.add_argument("--watch", required=True)
    ap.add_argument("--bootstrap")
    ap.add_argument("--out", default="live-wanpulse.json")
    a = ap.parse_args()

    spec = {f["name"]: f for f in json.load(open(a.scenario))["faults"]}
    faults = {}
    for line in open(a.truth):
        r = json.loads(line)
        f = faults.setdefault(r["fault"], {"name": r["fault"], "expected": expected(spec[r["fault"]]["match"])})
        f["start" if r["event"] == "fault_start" else "end"] = r["atMs"]
        if r["event"] == "fault_start":
            f["tunnels"] = set(r.get("affectedTunnels", []))
    watch = [json.loads(l) for l in open(a.watch)]
    watch = [w for w in watch if "open" in w]

    rows, false_roots = [], defaultdict(int)
    for f in sorted(faults.values(), key=lambda x: x["start"]):
        seen = [w["atMs"] for w in watch if w["atMs"] >= f["start"] and any(o["root"] == f["expected"] for o in w["open"])]
        first = seen[0] if seen else None
        cleared = None
        if first and "end" in f:
            after = [w for w in watch if w["atMs"] >= f["end"]]
            gone = [w["atMs"] for w in after if not any(o["root"] == f["expected"] for o in w["open"])]
            cleared = gone[0] if gone else None
        conf = next((o["confidence"] for w in watch if w["atMs"] >= (first or 0)
                     for o in w["open"] if o["root"] == f["expected"]), None)
        rows.append({"fault": f["name"], "expected": f["expected"], "diagnosed": first is not None,
                     "timeToDiagnoseS": round((first - f["start"]) / 1000, 1) if first else None,
                     "resolvedAfterEndS": round((cleared - f["end"]) / 1000, 1) if cleared else None,
                     "confidence": conf, "affectedTunnels": len(f.get("tunnels", []))})

    # incidents whose root matches no fault active at that moment (with 60 s of slack after it ends)
    false_ids = {}
    for w in watch:
        for o in w["open"]:
            ok = any(o["root"] == f["expected"] and f["start"] <= w["atMs"] <= f.get("end", 1e18) + 60_000
                     for f in faults.values())
            if not ok:
                false_ids[o["id"]] = o["root"]
    incidents = {o["id"] for w in watch for o in w["open"]}

    out = {"faults": rows, "faultsDiagnosed": sum(r["diagnosed"] for r in rows), "faultCount": len(rows),
           "timeToDiagnoseMedianS": statistics.median([r["timeToDiagnoseS"] for r in rows if r["diagnosed"]]) if rows else None,
           "incidentsOpened": len(incidents), "falseIncidents": [{"id": k, "root": v} for k, v in false_ids.items()]}
    if a.bootstrap:
        opened = count_alerts(a.bootstrap, faults)
        out["wanpulseSlaAlertsOpened"] = len(opened)
        out["alertsPerIncident"] = round(len(opened) / max(1, len(incidents)), 1)
    json.dump(out, open(a.out, "w"), indent=1)
    print(f'{"fault":<24} {"expected root cause":<28} {"diagnosed":<9} {"TTD s":>6} {"resolve s":>9} conf')
    for r in rows:
        print(f'{r["fault"]:<24} {r["expected"]:<28} {str(r["diagnosed"]):<9} {str(r["timeToDiagnoseS"]):>6} '
              f'{str(r["resolvedAfterEndS"]):>9} {r["confidence"]}')
    print(json.dumps({k: v for k, v in out.items() if k != "faults"}))

if __name__ == "__main__":
    main()
