package io.nettriage.bench;

import io.nettriage.core.ChangeGroup;
import io.nettriage.core.WindowView;
import io.nettriage.core.localize.Localizer;
import io.nettriage.core.model.Element;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simpler ways to pick a root cause, run on exactly the same degraded/healthy tunnel
 * states as the engine, so the comparison isolates the localization step.
 */
public final class Baselines {

    private Baselines() {
    }

    /**
     * Looks only at alarms: repeatedly blames whatever element covers the most degraded
     * tunnels not yet explained. Never asks how many of that element's tunnels are fine.
     */
    public static List<String> alarmsOnly(byte[] state, List<Localizer.Candidate> candidates) {
        return greedyCover(state, candidates, 0.0);
    }

    /**
     * Threshold set cover, in the spirit of SCORE-style shared-risk localization: only
     * elements with at least {@code threshold} of their tunnels degraded qualify; among
     * those, repeatedly take the one covering the most unexplained degraded tunnels.
     */
    public static List<String> thresholdCover(byte[] state, List<Localizer.Candidate> candidates, double threshold) {
        return greedyCover(state, candidates, threshold);
    }

    /**
     * What a tired on-call does first: if anything was rolled out to the affected
     * routers recently, blame the most recent rollout. Otherwise threshold set cover.
     */
    public static List<String> blameLatestChange(byte[] state, List<Localizer.Candidate> candidates, WindowView view) {
        ChangeGroup latest = null;
        for (Map.Entry<Element, ChangeGroup> en : view.changes().entrySet()) {
            int[] k = view.count(view.cover(en.getKey()));
            if (k[1] > 0 && (latest == null || en.getValue().lastApplied() > latest.lastApplied())) {
                latest = en.getValue();
            }
        }
        if (latest != null) {
            return List.of(latest.element().id());
        }
        return thresholdCover(state, candidates, 0.8);
    }

    private static List<String> greedyCover(byte[] state, List<Localizer.Candidate> candidates, double threshold) {
        Set<Integer> remaining = new HashSet<>();
        for (int i = 0; i < state.length; i++) {
            if (state[i] == Localizer.DEGRADED) {
                remaining.add(i);
            }
        }
        List<String> out = new ArrayList<>();
        while (remaining.size() >= 2 && out.size() < 8) {
            Localizer.Candidate best = null;
            int bestGain = 1;
            for (Localizer.Candidate c : candidates) {
                int obs = 0;
                int bad = 0;
                int gain = 0;
                for (int i : c.cover()) {
                    if (state[i] != Localizer.EXCLUDED) {
                        obs++;
                    }
                    if (state[i] == Localizer.DEGRADED) {
                        bad++;
                        if (remaining.contains(i)) {
                            gain++;
                        }
                    }
                }
                if (obs == 0 || (double) bad / obs < threshold) {
                    continue;
                }
                if (gain > bestGain || gain == bestGain && best != null && better(c, best)) {
                    best = c;
                    bestGain = gain;
                }
            }
            if (best == null) {
                break;
            }
            out.add(best.element().id());
            for (int i : best.cover()) {
                remaining.remove(i);
            }
        }
        return out;
    }

    private static boolean better(Localizer.Candidate a, Localizer.Candidate b) {
        if (a.cover().length != b.cover().length) {
            return a.cover().length < b.cover().length;
        }
        return a.element().kind().ordinal() < b.element().kind().ordinal();
    }
}
