#!/usr/bin/env python3
"""Polls NetTriage once a second and logs, in wall-clock time, which root causes are
open. Run alongside a live scenario; score_live.py turns the log into results."""
import json, sys, time, urllib.request
url, out, duration = sys.argv[1], sys.argv[2], float(sys.argv[3])
end = time.time() + duration
with open(out, "w") as f:
    while time.time() < end:
        try:
            inc = json.load(urllib.request.urlopen(url + "/api/incidents?all=false", timeout=5))
            f.write(json.dumps({"atMs": int(time.time() * 1000),
                                "open": [{"id": i["id"], "root": i["rootCause"], "confidence": i["confidence"],
                                          "degraded": i["degradedNow"]} for i in inc]}) + "\n")
            f.flush()
        except Exception as e:
            f.write(json.dumps({"atMs": int(time.time() * 1000), "error": str(e)}) + "\n")
        time.sleep(1)
