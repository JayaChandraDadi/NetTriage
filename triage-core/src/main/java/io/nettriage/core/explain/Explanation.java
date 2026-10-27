package io.nettriage.core.explain;

import java.util.List;
import java.util.Set;

/** The human-facing side of one root cause. */
public record Explanation(
        String headline,
        Signature signature,
        List<Evidence> evidence,
        List<AlternativeView> alternatives,
        List<Action> actions,
        Set<Integer> sites,
        long onsetAt) {
}
