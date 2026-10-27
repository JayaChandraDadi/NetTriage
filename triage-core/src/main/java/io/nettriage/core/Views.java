package io.nettriage.core;

import java.util.List;

/** Read-only answers to queries against the engine. */
public final class Views {

    private Views() {
    }

    /** One tunnel now, against its own baseline. */
    public record TunnelView(
            String key, int siteId, String systemIp, String region, String color, String hub,
            String health, String symptoms, double lossPct, double baselineLossPct,
            Double latencyMs, double baselineLatencyMs, Double jitterMs, double baselineJitterMs,
            Long degradedSince) {
    }

    /** Filter for tunnel queries; null fields match anything. */
    public record TunnelFilter(Integer siteId, String color, String region, String hub, Boolean degradedOnly) {
        public static TunnelFilter all() {
            return new TunnelFilter(null, null, null, null, null);
        }
    }

    public record TunnelQuery(int matched, int degraded, int healthy, int unknown, List<TunnelView> tunnels,
                              boolean truncated) {
    }

    /** A config rollout as NetTriage sees it. */
    public record ChangeView(String element, String deploymentId, String label, String color, int routers,
                             long firstApplied, long lastApplied, List<String> diff, int degraded, int observed) {
    }

    /** Degradation by region and transport, for an at-a-glance map. */
    public record Cell(String region, String color, int observed, int degraded) {
    }

    public record HubCell(String hub, String color, int observed, int degraded) {
    }

    public record Grid(long windowEnd, List<String> regions, List<String> colors, List<Cell> cells, List<HubCell> hubs) {
    }

    public record Status(long windowsProcessed, long gapWindows, Long lastWindowEnd, int tunnelsKnown, int observed,
                         int degraded, int unexplained, int openIncidents, double meanProcessMs, double maxProcessMs,
                         int changeGroups, int devicesKnown, double backgroundNoisePct) {
    }

    /** One window's outcome. */
    public record WindowReport(long windowEnd, boolean gap, int observed, int degraded, int unexplained,
                               List<String> causes, List<String> openedIncidents, double processMs) {
    }

    /**
     * Why the root cause beats another explanation.
     *
     * @param margin log-likelihood lost by swapping; under about 4 the two are hard to tell apart
     */
    public record Comparison(String incident, String rootCause, int rootCovered, int rootDegraded,
                             String alternative, int altCovered, int altDegraded, int altOverlap,
                             Double margin, String verdict) {
    }
}
