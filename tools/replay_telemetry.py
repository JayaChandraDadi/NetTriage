#!/usr/bin/env python3
"""Replays synthetic WanPulse-style tunnel telemetry into NetTriage for routers that
EdgeFleet manages, degrading one transport on the routers that applied a rollout,
each from its own apply time. Used by scripts/live-edgefleet.sh.

  replay_telemetry.py --nettriage URL --edgefleet URL --deployment ID [--color biz-internet]
                      [--loss 5] [--before 180] [--after 120]
"""
import argparse, json, math, random, urllib.request

def get(url):
    with urllib.request.urlopen(url, timeout=10) as r:
        return json.load(r)

def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--nettriage", default="http://localhost:8095")
    ap.add_argument("--edgefleet", default="http://localhost:8090")
    ap.add_argument("--deployment", required=True)
    ap.add_argument("--color", default="biz-internet")
    ap.add_argument("--loss", type=float, default=5.0, help="extra loss %% on affected tunnels")
    ap.add_argument("--before", type=int, default=180)
    ap.add_argument("--after", type=int, default=120)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    rnd = random.Random(a.seed)

    devices = [d for d in get(a.edgefleet + "/api/devices") if d["state"] == "ONBOARDED"]
    serial_ip = {d["serial"]: d["systemIp"] for d in devices}
    applied, after_id = {}, 0
    while True:
        events = get(f"{a.edgefleet}/api/events?afterId={after_id}&limit=500")
        if not events:
            break
        for e in events:
            after_id = max(after_id, e["id"])
            if e["kind"] == "config.applied" and e.get("deploymentId") == a.deployment and e["serial"] in serial_ip:
                ip = serial_ip[e["serial"]]
                ms = int(__import__("datetime").datetime.fromisoformat(e["at"].replace("Z", "+00:00")).timestamp() * 1000)
                applied.setdefault(ip, ms)
    if not applied:
        raise SystemExit("no router applied deployment " + a.deployment)
    t0 = min(applied.values())
    base = {"us-west": (8, 34), "us-east": (34, 8), "eu-west": (78, 42)}
    access = {"mpls": (12, 0.0005, 1.5), "biz-internet": (18, 0.002, 4.0)}
    hubs = ["10.0.0.1", "10.0.0.2"]
    start = (t0 - a.before * 1000) // 10_000 * 10_000
    end = t0 + a.after * 1000
    sent = 0
    w = start
    while w <= end:
        tunnels = []
        for d in devices:
            ip, region = d["systemIp"], d["region"]
            for color, (acc, loss_p, jit) in access.items():
                for hi, hub in enumerate(hubs):
                    p = loss_p
                    if color == a.color and ip in applied and w >= applied[ip] + 10_000:
                        p += a.loss / 100
                    lost = sum(1 for _ in range(100) if rnd.random() < p)
                    lat = base.get(region, (40, 40))[hi] + acc + abs(rnd.gauss(0, 1.5))
                    tunnels.append({"tunnelKey": f"{ip}:{color}->{hub}:{color}", "siteId": d["siteId"], "region": region,
                                    "localSystemIp": ip, "localColor": color, "remoteSystemIp": hub, "windowEnd": w,
                                    "probesSent": 100, "lossPct": float(lost), "latencyMs": lat,
                                    "jitterMs": jit * (0.6 + 0.8 * rnd.random()), "sufficientData": True})
        post(a.nettriage + "/api/replay/window", {"windowEnd": w, "tunnels": tunnels})
        sent += 1
        w += 10_000
    print(json.dumps({"windows": sent, "routersApplied": len(applied), "firstApplied": t0}))

if __name__ == "__main__":
    main()
