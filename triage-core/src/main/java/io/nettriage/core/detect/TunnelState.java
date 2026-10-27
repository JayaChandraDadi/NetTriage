package io.nettriage.core.detect;

import io.nettriage.core.model.TunnelWindow;

/**
 * Learned normal behavior of one tunnel and its recent verdicts.
 *
 * <p>The baseline is an exponentially weighted mean and variance of latency and jitter,
 * plus a decayed probe-loss rate. It learns only from windows judged healthy, so a
 * long fault cannot teach the tunnel that degradation is normal.
 *
 * <p>Single windows are noisy: 100 probes on a clean circuit still lose one now and
 * then. A tunnel counts as degraded only when two of its last three windows were, and
 * as healthy only when none were. Anything in between is {@link Health#UNKNOWN} and
 * stays out of the root-cause math rather than counting as evidence either way.
 */
public final class TunnelState {

    private long healthyWindows;
    private double latMean;
    private double latVar;
    private double jitMean;
    private double jitVar;
    private double lossSent;
    private double lossLost;

    private int history;        // last three raw verdicts, bit 0 = newest, 1 = degraded
    private int historyLength;
    private long[] recentEnds = new long[3];

    private Health health = Health.UNKNOWN;
    private long degradedSince = -1;
    private long lastWindowEnd = -1;

    // latest reading, for queries and evidence
    private int flags;
    private double lossPct;
    private Double latencyMs;
    private Double jitterMs;

    /** Judges one window and returns the smoothed verdict. */
    public Health observe(TunnelWindow w, DetectorSettings s) {
        lastWindowEnd = w.windowEnd();
        lossPct = w.lossPct();
        latencyMs = w.latencyMs();
        jitterMs = w.jitterMs();
        if (!w.sufficientData() || w.probesSent() <= 0) {
            flags = 0;
            return health = Health.UNKNOWN;
        }

        int raw = 0;
        boolean warm = healthyWindows >= s.warmupWindows();
        if (w.lossPct() >= s.blackoutPct()) {
            raw = Symptom.BLACKOUT | Symptom.LOSS;
        } else if (warm) {
            double sent = w.probesSent();
            double lost = w.lossPct() * sent / 100.0;
            double expected = baselineLossRate() * sent;
            if (w.lossPct() >= s.minLossPct()
                    && lost >= expected + Math.max(s.minExtraLost(), 4 * Math.sqrt(expected + 0.25))) {
                raw |= Symptom.LOSS;
            }
            if (w.latencyMs() != null
                    && w.latencyMs() - latMean > Math.max(s.minLatencyMs(), s.latencySigmas() * Math.sqrt(latVar))) {
                raw |= Symptom.LATENCY;
            }
            if (w.jitterMs() != null
                    && w.jitterMs() - jitMean > Math.max(s.minJitterMs(), s.jitterSigmas() * Math.sqrt(jitVar))) {
                raw |= Symptom.JITTER;
            }
        }
        flags = raw;

        if (raw == 0) {
            learn(w, s);
        }
        if (!warm && raw == 0) {
            return health = Health.UNKNOWN;
        }

        history = ((history << 1) | (raw != 0 ? 1 : 0)) & 0b111;
        historyLength = Math.min(3, historyLength + 1);
        recentEnds[2] = recentEnds[1];
        recentEnds[1] = recentEnds[0];
        recentEnds[0] = w.windowEnd();

        int bad = Integer.bitCount(history & ((1 << historyLength) - 1));
        if (bad >= 2) {
            if (health != Health.DEGRADED) {
                degradedSince = earliestRecentDegraded();
            }
            health = Health.DEGRADED;
        } else if (bad == 0) {
            health = Health.HEALTHY;
            degradedSince = -1;
        } else {
            // one of the last three: a blip starting, or a fault ending; don't guess
            health = Health.UNKNOWN;
        }
        return health;
    }

    private long earliestRecentDegraded() {
        for (int i = historyLength - 1; i >= 0; i--) {
            if ((history & (1 << i)) != 0) {
                return recentEnds[i];
            }
        }
        return lastWindowEnd;
    }

    private void learn(TunnelWindow w, DetectorSettings s) {
        double a = healthyWindows < s.warmupWindows() ? 1.0 / (healthyWindows + 1) : s.alpha();
        healthyWindows++;
        if (w.latencyMs() != null) {
            double d = w.latencyMs() - latMean;
            latMean += a * d;
            latVar = (1 - a) * (latVar + a * d * d);
        }
        if (w.jitterMs() != null) {
            double d = w.jitterMs() - jitMean;
            jitMean += a * d;
            jitVar = (1 - a) * (jitVar + a * d * d);
        }
        lossSent = (1 - a) * lossSent + w.probesSent();
        lossLost = (1 - a) * lossLost + w.lossPct() * w.probesSent() / 100.0;
    }

    public double baselineLossRate() {
        return lossSent <= 0 ? 0 : lossLost / lossSent;
    }

    public Health health() {
        return health;
    }

    /** Window end of the first degraded window in the current streak; -1 when not degraded. */
    public long degradedSince() {
        return degradedSince;
    }

    public long lastWindowEnd() {
        return lastWindowEnd;
    }

    public int flags() {
        return flags;
    }

    public double lossPct() {
        return lossPct;
    }

    public Double latencyMs() {
        return latencyMs;
    }

    public Double jitterMs() {
        return jitterMs;
    }

    public double baselineLatencyMs() {
        return latMean;
    }

    public double baselineJitterMs() {
        return jitMean;
    }

    public boolean warm(DetectorSettings s) {
        return healthyWindows >= s.warmupWindows();
    }

    /** Marks the tunnel as not reporting this window. */
    public void silent() {
        health = Health.UNKNOWN;
        flags = 0;
    }
}
