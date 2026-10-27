package io.nettriage.core.detect;

/**
 * How far a tunnel must move from its own normal before it counts as degraded.
 * Thresholds are relative to each tunnel's learned baseline, so an LTE tunnel with
 * 60 ms of normal latency and an MPLS tunnel with 20 ms are judged on their own terms.
 *
 * @param warmupWindows   windows of history before a tunnel is judged at all
 * @param alpha           EWMA weight of a new healthy window in the baseline
 * @param minLatencyMs    smallest latency rise that counts, however quiet the tunnel
 * @param latencySigmas   latency rise needed, in baseline standard deviations
 * @param minJitterMs     smallest jitter rise that counts
 * @param jitterSigmas    jitter rise needed, in baseline standard deviations
 * @param minLossPct      smallest loss that counts
 * @param minExtraLost    lost probes above the baseline expectation needed at minimum
 * @param blackoutPct     loss at or above which a window is a blackout
 */
public record DetectorSettings(
        int warmupWindows,
        double alpha,
        double minLatencyMs,
        double latencySigmas,
        double minJitterMs,
        double jitterSigmas,
        double minLossPct,
        double minExtraLost,
        double blackoutPct) {

    public static DetectorSettings defaults() {
        return new DetectorSettings(6, 0.05, 20, 4, 10, 4, 1.0, 2, 99.0);
    }
}
