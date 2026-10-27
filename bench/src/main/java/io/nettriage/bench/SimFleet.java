package io.nettriage.bench;

import io.nettriage.core.model.Tunnel;
import io.nettriage.core.model.TunnelWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A hub-and-spoke SD-WAN fleet and its tunnel noise, matching WanPulse's edge simulator:
 * every branch builds a tunnel to both hubs over each of its two transports; 40% of
 * sites are in us-west, 40% us-east, 20% eu-west; two thirds have MPLS and business
 * internet, one third business internet and LTE. Latency drifts as a mean-reverting
 * random walk and loss is drawn per probe, 10 probes per second.
 *
 * <p>Telemetry is produced directly as 10-second window aggregates (what WanPulse
 * publishes on {@code tunnel.health}), but each window is built from ten simulated
 * one-second probe intervals so its noise has the right shape.
 */
public final class SimFleet {

    public static final String WEST_HUB = "10.0.0.1";
    public static final String EAST_HUB = "10.0.0.2";
    public static final long WINDOW_MS = 10_000;
    static final int INTERVALS = 10;
    static final int PROBES = 10;

    enum Transport {
        MPLS("mpls", 12, 1.0, 0.0005, 1.5),
        BIZ("biz-internet", 18, 4.0, 0.002, 4.0),
        LTE("lte", 45, 12.0, 0.006, 12.0);

        final String color;
        final double accessMs;
        final double volatility;
        final double baseLoss;
        final double baseJitter;

        Transport(String color, double accessMs, double volatility, double baseLoss, double baseJitter) {
            this.color = color;
            this.accessMs = accessMs;
            this.volatility = volatility;
            this.baseLoss = baseLoss;
            this.baseJitter = baseJitter;
        }
    }

    /** One tunnel and its random-walk state. */
    static final class SimTunnel {
        final Tunnel tunnel;
        final Transport transport;
        final double baselineMs;
        double drift;

        SimTunnel(Tunnel tunnel, Transport transport, double baselineMs) {
            this.tunnel = tunnel;
            this.transport = transport;
            this.baselineMs = baselineMs;
        }
    }

    public record Site(int siteId, String systemIp, String region, List<String> colors) {
    }

    private final List<SimTunnel> tunnels = new ArrayList<>();
    private final List<Site> sites = new ArrayList<>();

    public SimFleet(int siteCount) {
        for (int i = 0; i < siteCount; i++) {
            int siteId = 100 + i;
            String ip = "10.1." + (i / 250) + "." + (i % 250 + 1);
            String region;
            double toWest;
            double toEast;
            switch (i % 10) {
                case 0, 1, 2, 3 -> { region = "us-west"; toWest = 8; toEast = 34; }
                case 4, 5, 6, 7 -> { region = "us-east"; toWest = 34; toEast = 8; }
                default -> { region = "eu-west"; toWest = 78; toEast = 42; }
            }
            List<Transport> ts = i % 3 == 2 ? List.of(Transport.BIZ, Transport.LTE) : List.of(Transport.MPLS, Transport.BIZ);
            List<String> colors = new ArrayList<>();
            for (Transport t : ts) {
                colors.add(t.color);
                for (String hub : List.of(WEST_HUB, EAST_HUB)) {
                    String key = ip + ":" + t.color + "->" + hub + ":" + t.color;
                    double base = (hub.equals(WEST_HUB) ? toWest : toEast) + t.accessMs;
                    tunnels.add(new SimTunnel(new Tunnel(key, siteId, ip, region, t.color, hub), t, base));
                }
            }
            sites.add(new Site(siteId, ip, region, List.copyOf(colors)));
        }
    }

    public List<Site> sites() {
        return sites;
    }

    public List<Tunnel> tunnels() {
        return tunnels.stream().map(t -> t.tunnel).toList();
    }

    /** Extra degradation on one tunnel at one instant. */
    public record Impairment(double loss, double latencyMs, double jitterMs) {
        public static final Impairment NONE = new Impairment(0, 0, 0);

        public Impairment plus(Impairment o) {
            return new Impairment(1 - (1 - loss) * (1 - o.loss), latencyMs + o.latencyMs, jitterMs + o.jitterMs);
        }
    }

    public interface ImpairmentSource {
        Impairment at(Tunnel t, long timeMs);
    }

    /** Produces the window ending at {@code windowEnd} for every tunnel. */
    public List<TunnelWindow> window(long windowEnd, ImpairmentSource faults, SplittableRandom rnd) {
        List<TunnelWindow> out = new ArrayList<>(tunnels.size());
        long start = windowEnd - WINDOW_MS;
        for (SimTunnel st : tunnels) {
            Transport tr = st.transport;
            int sent = 0;
            int lost = 0;
            double latSum = 0;
            double jitSum = 0;
            int received = 0;
            for (int k = 1; k <= INTERVALS; k++) {
                long t = start + k * (WINDOW_MS / INTERVALS);
                Impairment imp = faults.at(st.tunnel, t);
                st.drift += -0.2 * st.drift + rnd.nextGaussian() * tr.volatility * 0.5;
                double p = Math.min(1.0, tr.baseLoss + imp.loss() - tr.baseLoss * imp.loss());
                int l = 0;
                for (int j = 0; j < PROBES; j++) {
                    if (rnd.nextDouble() < p) {
                        l++;
                    }
                }
                sent += PROBES;
                lost += l;
                int r = PROBES - l;
                if (r > 0) {
                    double lat = Math.max(1.0, st.baselineMs + st.drift + imp.latencyMs()
                            + Math.abs(rnd.nextGaussian()) * tr.volatility * 0.3);
                    double jit = Math.max(0.1, tr.baseJitter * (0.6 + 0.8 * rnd.nextDouble())
                            + imp.jitterMs() * (0.7 + 0.6 * rnd.nextDouble()));
                    latSum += lat * r;
                    jitSum += jit * r;
                    received += r;
                }
            }
            double lossPct = 100.0 * lost / sent;
            out.add(new TunnelWindow(st.tunnel, windowEnd, sent, lossPct,
                    received == 0 ? null : latSum / received, received == 0 ? null : jitSum / received, true));
        }
        return out;
    }
}
