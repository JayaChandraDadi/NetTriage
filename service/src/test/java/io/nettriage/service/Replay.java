package io.nettriage.service;

import io.nettriage.bench.Scenario;
import io.nettriage.bench.SimFleet;
import io.nettriage.core.model.TunnelWindow;
import io.nettriage.service.api.ReplayController;
import io.nettriage.service.ingest.TunnelHealthMessage;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.SplittableRandom;

/** Streams a benchmark scenario into a running service over the replay API. */
final class Replay {

    private Replay() {
    }

    static void run(RestClient http, Scenario sc, long until) {
        SimFleet fleet = new SimFleet(200);
        SplittableRandom rnd = new SplittableRandom(sc.seed());
        int ci = 0;
        for (int w = 1; w <= sc.windows(); w++) {
            long end = sc.startMs() + w * SimFleet.WINDOW_MS;
            if (end > until) {
                break;
            }
            int from = ci;
            while (ci < sc.changes().size() && sc.changes().get(ci).appliedAt() <= end) {
                ci++;
            }
            if (ci > from) {
                http.post().uri("/api/replay/changes").body(sc.changes().subList(from, ci)).retrieve().toBodilessEntity();
            }
            List<TunnelHealthMessage> msgs = fleet.window(end, sc.impairments(), rnd).stream().map(Replay::message).toList();
            http.post().uri("/api/replay/window").body(new ReplayController.WindowBatch(end, msgs)).retrieve().toBodilessEntity();
        }
    }

    static TunnelHealthMessage message(TunnelWindow w) {
        var t = w.tunnel();
        return new TunnelHealthMessage(t.key(), t.siteId(), t.region(), t.systemIp(), t.color(), t.hub(), w.windowEnd(),
                w.probesSent(), w.lossPct(), w.latencyMs(), w.jitterMs(), w.sufficientData());
    }
}
