package io.nettriage.core.model;

/**
 * Aggregated measurements for one tunnel over one closed time window, as published by
 * WanPulse on {@code tunnel.health}.
 *
 * @param windowEnd      event-time end of the window, epoch ms
 * @param probesSent     probes sent in the window
 * @param lossPct        share of probes lost, 0 to 100
 * @param latencyMs      mean latency over received probes; null when nothing came back
 * @param jitterMs       mean jitter over received probes; null when nothing came back
 * @param sufficientData false when too few probes were sent to judge the tunnel
 */
public record TunnelWindow(
        Tunnel tunnel,
        long windowEnd,
        long probesSent,
        double lossPct,
        Double latencyMs,
        Double jitterMs,
        boolean sufficientData) {
}
