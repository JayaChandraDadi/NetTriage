package io.nettriage.bench;

import io.nettriage.core.model.ConfigChange;
import io.nettriage.core.model.Tunnel;
import io.nettriage.core.model.TunnelWindow;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Streams sixteen minutes of a simulated 200-site fleet into a running NetTriage service
 * through its replay API, ending now: a provider brownout and a circuit outage that both
 * resolve, then a bad MTU rollout and a hub circuit problem that are still open.
 *
 * <pre>  java -cp bench-0.1.0-exec.jar -Dloader.main=io.nettriage.bench.DemoFeed org.springframework.boot.loader.launch.PropertiesLauncher [http://localhost:8095]</pre>
 */
public final class DemoFeed {

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8095";
        SimFleet fleet = new SimFleet(200);
        long now = System.currentTimeMillis() / SimFleet.WINDOW_MS * SimFleet.WINDOW_MS;
        long t0 = now - 16 * 60_000L;
        long m = 60_000L;

        List<Scenario.Fault> faults = new ArrayList<>();
        faults.add(new Scenario.Fault("brownout", t -> t.color().equals("biz-internet") && t.region().equals("us-east"),
                new SimFleet.Impairment(0.03, 60, 15), t0 + 3 * m, t0 + 7 * m, null));
        faults.add(new Scenario.Fault("lte down", t -> t.siteId() == 141 && t.color().equals("lte"),
                new SimFleet.Impairment(1, 0, 0), t0 + 5 * m, t0 + 9 * m, null));
        faults.add(new Scenario.Fault("hub circuit", t -> t.hub().equals(SimFleet.WEST_HUB) && t.color().equals("mpls"),
                new SimFleet.Impairment(0.03, 45, 8), t0 + 13 * m + 30_000, Long.MAX_VALUE, null));

        // staged rollout of an MTU change on biz-internet: canary at 10:00, next batch at 12:00
        String dep = "6f1c2a9e-4b7d-4e21-9c3a-8d5e0b7f2a41";
        List<SimFleet.Site> targets = new ArrayList<>(fleet.sites());
        java.util.Collections.shuffle(targets, new java.util.Random(11));
        Map<String, Long> applied = new LinkedHashMap<>();
        List<ConfigChange> changes = new ArrayList<>();
        SplittableRandom r = new SplittableRandom(5);
        for (int i = 0; i < 60; i++) {
            SimFleet.Site s = targets.get(i);
            long at = (i < 10 ? t0 + 10 * m : t0 + 12 * m) + r.nextInt(20_000);
            applied.put(s.systemIp(), at);
            changes.add(new ConfigChange(dep, "branch v4", s.systemIp(), s.siteId(), at, i < 10 ? 7 : 8, "biz-internet",
                    List.of("~ transports[color=biz-internet].mtu: 1500 -> 1380")));
        }
        changes.sort((a, b) -> Long.compare(a.appliedAt(), b.appliedAt()));
        faults.add(new Scenario.Fault("rollout", t -> t.color().equals("biz-internet") && applied.containsKey(t.systemIp()),
                new SimFleet.Impairment(0.05, 0, 0), t0, Long.MAX_VALUE, t -> applied.get(t.systemIp())));

        Scenario sc = new Scenario("demo", 1, t0, 96, faults, changes, List.of(), t0, now, now, "demo");
        JsonMapper json = JsonMapper.builder().build();
        HttpClient http = HttpClient.newHttpClient();
        SplittableRandom rnd = new SplittableRandom(3);
        int ci = 0;
        for (int w = 1; w <= sc.windows(); w++) {
            long end = t0 + w * SimFleet.WINDOW_MS;
            int from = ci;
            while (ci < changes.size() && changes.get(ci).appliedAt() <= end) {
                ci++;
            }
            if (ci > from) {
                post(http, base + "/api/replay/changes", json.writeValueAsString(changes.subList(from, ci)));
            }
            List<Map<String, Object>> tunnels = new ArrayList<>();
            for (TunnelWindow tw : fleet.window(end, sc.impairments(), rnd)) {
                Tunnel t = tw.tunnel();
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("tunnelKey", t.key());
                msg.put("siteId", t.siteId());
                msg.put("region", t.region());
                msg.put("localSystemIp", t.systemIp());
                msg.put("localColor", t.color());
                msg.put("remoteSystemIp", t.hub());
                msg.put("windowEnd", end);
                msg.put("probesSent", tw.probesSent());
                msg.put("lossPct", tw.lossPct());
                msg.put("latencyMs", tw.latencyMs());
                msg.put("jitterMs", tw.jitterMs());
                msg.put("sufficientData", true);
                tunnels.add(msg);
            }
            post(http, base + "/api/replay/window", json.writeValueAsString(Map.of("windowEnd", end, "tunnels", tunnels)));
        }
        System.out.println("streamed " + sc.windows() + " windows and " + changes.size() + " config changes to " + base);
    }

    private static void post(HttpClient http, String url, String body) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) {
            throw new IllegalStateException(url + " -> " + res.statusCode() + " " + res.body());
        }
    }
}
