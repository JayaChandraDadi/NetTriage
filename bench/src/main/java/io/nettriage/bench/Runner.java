package io.nettriage.bench;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.Views;
import io.nettriage.core.incident.IncidentView;
import io.nettriage.core.model.ConfigChange;
import io.nettriage.core.model.TunnelWindow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/** Runs one scenario through the engine and the baselines and scores both. */
public final class Runner {

    /**
     * @param engineRoots      root causes of open incidents at evaluation time
     * @param diagnosedAfterMs per truth element, time from fault start until an open incident
     *                         first named it; missing when never
     * @param falseIncidents   incidents opened during the run whose root cause was never a truth element
     * @param alerts           tunnel-level SLA alerts a per-tunnel alerting rule would have raised
     * @param incidentsOpened  incidents NetTriage opened during the run
     * @param resolvedAfterMs  per truth element, time from fault end until its incident resolved
     */
    public record Result(
            String type, long seed, String note, List<String> truth,
            List<String> engineRoots, List<String> localizerRoots,
            List<String> alarmsOnly, List<String> thresholdCover, List<String> thresholdCoverLoose, List<String> blameChange,
            Map<String, Long> diagnosedAfterMs, Map<String, Long> resolvedAfterMs,
            int falseIncidents, List<String> falseRoots, int alerts, int incidentsOpened, String confidenceAtEval,
            double meanWindowMs, int tunnels) {

        public boolean engineExact() {
            return Set.copyOf(engineRoots).equals(Set.copyOf(truth));
        }

        public boolean localizerExact() {
            return Set.copyOf(localizerRoots).equals(Set.copyOf(truth));
        }
    }

    private Runner() {
    }

    public static Result run(Scenario sc, int sites, TriageEngine.Settings settings) {
        SimFleet fleet = new SimFleet(sites);
        SplittableRandom rnd = new SplittableRandom(sc.seed() * 31 + sc.type().hashCode());
        TriageEngine engine = new TriageEngine(settings);
        SimFleet.ImpairmentSource imp = sc.impairments();

        final List<String>[] snapshot = newSnapshot();
        engine.setListener((view, candidates, result) -> {
            if (snapshot[0] == null && view.windowEnd() >= sc.evalAt()) {
                snapshot[0] = result.causes().stream().map(c -> c.fit().element().id()).toList();
                snapshot[1] = Baselines.alarmsOnly(view.state(), candidates);
                snapshot[2] = Baselines.thresholdCover(view.state(), candidates, 0.8);
                snapshot[3] = Baselines.blameLatestChange(view.state(), candidates, view);
                snapshot[4] = Baselines.thresholdCover(view.state(), candidates, 0.5);
            }
        });

        Set<String> truth = new HashSet<>(sc.truth());
        Map<String, Long> diagnosed = new LinkedHashMap<>();
        Map<String, Long> resolved = new LinkedHashMap<>();
        List<String> engineRoots = null;
        String confidence = null;
        int changeIdx = 0;
        List<ConfigChange> changes = sc.changes();
        Map<String, Integer> streak = new HashMap<>();
        int alerts = 0;
        double totalMs = 0;
        int processed = 0;

        for (int w = 1; w <= sc.windows(); w++) {
            long end = sc.startMs() + w * SimFleet.WINDOW_MS;
            while (changeIdx < changes.size() && changes.get(changeIdx).appliedAt() <= end) {
                engine.addChange(changes.get(changeIdx++));
            }
            List<TunnelWindow> obs = fleet.window(end, imp, rnd);
            for (TunnelWindow tw : obs) {
                boolean bad = tw.lossPct() > 1.0 || tw.latencyMs() != null && tw.latencyMs() > 150
                        || tw.jitterMs() != null && tw.jitterMs() > 30 || tw.latencyMs() == null;
                int s = bad ? streak.merge(tw.tunnel().key(), 1, Integer::sum) : 0;
                if (!bad) {
                    streak.remove(tw.tunnel().key());
                }
                if (s == 2) {
                    alerts++;
                }
            }
            Views.WindowReport rep = engine.processWindow(end, obs);
            totalMs += rep.processMs();
            processed++;

            List<IncidentView> open = engine.incidents(false);
            for (IncidentView i : open) {
                if (truth.contains(i.rootCause()) && !diagnosed.containsKey(i.rootCause()) && end >= sc.faultStart()) {
                    diagnosed.put(i.rootCause(), end - sc.faultStart());
                }
            }
            if (engineRoots == null && end >= sc.evalAt()) {
                engineRoots = open.stream().map(IncidentView::rootCause).distinct().toList();
                confidence = open.stream().filter(i -> truth.contains(i.rootCause())).map(IncidentView::confidence)
                        .findFirst().orElse(open.isEmpty() ? null : open.get(0).confidence());
            }
        }

        int falseIncidents = 0;
        List<String> falseRoots = new ArrayList<>();
        int opened = 0;
        for (IncidentView i : engine.incidents(true)) {
            opened++;
            boolean everTruth = truth.contains(i.rootCause())
                    || i.history().stream().anyMatch(h -> truth.contains(h.rootCause()));
            if (!everTruth) {
                falseIncidents++;
                falseRoots.add(i.rootCause() + "@" + (i.openedAt() - sc.faultStart()) / 1000 + "s/" + i.peakDegraded()
                        + "t/" + i.confidence() + (i.history().size() > 1 ? "/hist=" + i.history().size() : ""));
            }
            if (truth.contains(i.rootCause()) && i.resolvedAt() != null && sc.faultEnd() != Long.MAX_VALUE
                    && i.resolvedAt() >= sc.faultEnd()) {
                resolved.putIfAbsent(i.rootCause(), i.resolvedAt() - sc.faultEnd());
            }
        }
        return new Result(sc.type(), sc.seed(), sc.note(), sc.truth(),
                engineRoots == null ? List.of() : engineRoots,
                orEmpty(snapshot[0]), orEmpty(snapshot[1]), orEmpty(snapshot[2]), orEmpty(snapshot[4]), orEmpty(snapshot[3]),
                diagnosed, resolved, falseIncidents, falseRoots, alerts, opened, confidence,
                processed == 0 ? 0 : totalMs / processed, fleet.tunnels().size());
    }

    @SuppressWarnings("unchecked")
    private static List<String>[] newSnapshot() {
        return new List[5];
    }

    private static List<String> orEmpty(List<String> l) {
        return l == null ? List.of() : l;
    }

    static List<String> sorted(List<String> l) {
        List<String> s = new ArrayList<>(l);
        s.sort(null);
        return s;
    }
}
