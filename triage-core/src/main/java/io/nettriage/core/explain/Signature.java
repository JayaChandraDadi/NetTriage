package io.nettriage.core.explain;

/**
 * What the degradation looks like on the affected tunnels: medians over the tunnels a
 * cause explains, relative to each tunnel's own baseline.
 *
 * @param kind            blackout, loss, latency, jitter, or a combination like loss+latency
 * @param blackoutShare   share of affected tunnels with every probe lost
 */
public record Signature(
        String kind,
        double lossPct,
        double baselineLossPct,
        double latencyRiseMs,
        double jitterRiseMs,
        double blackoutShare,
        int tunnels) {

    public String describe() {
        if (tunnels == 0) {
            return "No affected tunnels in the latest window.";
        }
        if ("blackout".equals(kind)) {
            return String.format("Every probe lost on %d of %d affected tunnels: a loss of connectivity, not a slowdown.",
                    Math.round(blackoutShare * tunnels), tunnels);
        }
        StringBuilder sb = new StringBuilder("Typical affected tunnel: ");
        sb.append(String.format("loss %.1f%% (normally %.1f%%)", lossPct, baselineLossPct));
        sb.append(String.format(", latency %+d ms", Math.round(latencyRiseMs)));
        sb.append(String.format(", jitter %+d ms", Math.round(jitterRiseMs)));
        sb.append(" against its own baseline.");
        return sb.toString();
    }
}
