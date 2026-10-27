package io.nettriage.core.incident;

import io.nettriage.core.explain.Action;
import io.nettriage.core.explain.AlternativeView;
import io.nettriage.core.explain.Evidence;
import io.nettriage.core.explain.Signature;

import java.util.List;

/**
 * Immutable snapshot of an incident: one root cause and the tunnels it explains, from
 * the first window it was seen until it has been quiet for a few windows.
 *
 * @param rootCause     element id, e.g. {@code provider/biz-internet/us-west}
 * @param margin        log-likelihood lead over the best competing explanation, nats
 * @param p             estimated share of the cause's tunnels it degrades
 * @param history       root-cause changes over the incident's life, oldest first
 * @param timeline      degraded tunnels attributed to this incident, per window
 */
public record IncidentView(
        String id,
        String state,
        String rootCause,
        String rootCauseKind,
        String description,
        String headline,
        String confidence,
        double margin,
        int degradedNow,
        int coveredNow,
        double p,
        int peakDegraded,
        int sitesAffected,
        List<Integer> sites,
        long onsetAt,
        long openedAt,
        long lastSeenAt,
        Long resolvedAt,
        Signature signature,
        List<Evidence> evidence,
        List<AlternativeView> alternatives,
        List<Action> actions,
        List<RootCauseChange> history,
        List<Point> timeline) {

    public record RootCauseChange(long at, String rootCause) {
    }

    public record Point(long at, int degraded) {
    }

    public boolean open() {
        return "OPEN".equals(state);
    }
}
