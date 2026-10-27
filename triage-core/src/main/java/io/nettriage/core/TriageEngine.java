package io.nettriage.core;

import io.nettriage.core.detect.DetectorSettings;
import io.nettriage.core.detect.Health;
import io.nettriage.core.detect.Symptom;
import io.nettriage.core.detect.TunnelState;
import io.nettriage.core.incident.IncidentTracker;
import io.nettriage.core.incident.IncidentView;
import io.nettriage.core.localize.Localizer;
import io.nettriage.core.model.ConfigChange;
import io.nettriage.core.model.Element;
import io.nettriage.core.model.Tunnel;
import io.nettriage.core.model.TunnelWindow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The diagnosis pipeline for one fleet: windows of tunnel telemetry and config changes
 * in, incidents with root causes and evidence out.
 *
 * <p>Call {@link #processWindow} once per closed telemetry window, in order. All public
 * methods are synchronized; queries return immutable snapshots.
 */
public final class TriageEngine {

    /**
     * @param changeLookbackMs     a rollout stays a suspect this long after its last router applied it
     * @param resolveAfterWindows  quiet windows before an incident is resolved
     * @param minObservedShare     a window with fewer reporting tunnels than this share of the
     *                             known fleet is a telemetry gap and is skipped, not diagnosed
     */
    public record Settings(
            DetectorSettings detector,
            Localizer.Settings localizer,
            long changeLookbackMs,
            int resolveAfterWindows,
            double minObservedShare,
            int timelineCap,
            int retainResolved) {

        public static Settings defaults() {
            return new Settings(DetectorSettings.defaults(), Localizer.Settings.defaults(), 30 * 60_000L, 3, 0.5, 360, 200);
        }

        public Settings withLocalizer(Localizer.Settings l) {
            return new Settings(detector, l, changeLookbackMs, resolveAfterWindows, minObservedShare, timelineCap,
                    retainResolved);
        }
    }

    private final Settings settings;
    private final Topology topology = new Topology();
    private final List<TunnelState> states = new ArrayList<>();
    private Localizer localizer;
    private double noise;
    private long noiseWindows;
    private final IncidentTracker tracker;
    private final Map<String, ChangeGroup> changeGroups = new LinkedHashMap<>();
    private final Map<String, DeviceStatus> devices = new HashMap<>();
    private final Set<String> seenChanges = new HashSet<>();

    private long windowsProcessed;
    private long gapWindows;
    private Long lastWindowEnd;
    private double totalProcessMs;
    private double maxProcessMs;
    private byte[] lastState = new byte[0];
    private Localizer.Result lastResult = Localizer.Result.empty(0);
    private Map<Element, ChangeGroup> lastChanges = Map.of();
    private WindowListener listener;

    /** Sees each diagnosed window's raw inputs and result; for benchmarks and debugging. */
    public interface WindowListener {
        void onWindow(WindowView view, List<Localizer.Candidate> candidates, Localizer.Result result);
    }

    public synchronized void setListener(WindowListener listener) {
        this.listener = listener;
    }

    public TriageEngine(Settings settings) {
        this.settings = settings;
        this.localizer = new Localizer(settings.localizer());
        this.noise = settings.localizer().eps();
        this.tracker = new IncidentTracker(settings.resolveAfterWindows(), settings.timelineCap(), settings.retainResolved());
    }

    public TriageEngine() {
        this(Settings.defaults());
    }

    /** Records a config change; duplicates (same router, same version) are ignored. */
    public synchronized void addChange(ConfigChange c) {
        if (!seenChanges.add(c.systemIp() + "#" + c.version())) {
            return;
        }
        changeGroups.computeIfAbsent(c.groupKey(), k -> new ChangeGroup(c)).add(c);
    }

    public synchronized void setDeviceStatus(DeviceStatus s) {
        devices.put(s.systemIp(), s);
    }

    /** Diagnoses one closed window. Observations for tunnels never seen before register them. */
    public synchronized Views.WindowReport processWindow(long windowEnd, Collection<TunnelWindow> observations) {
        long t0 = System.nanoTime();
        boolean[] seen = new boolean[topology.size() + observations.size()];
        for (TunnelWindow w : observations) {
            int idx = topology.register(w.tunnel());
            while (states.size() <= idx) {
                states.add(new TunnelState());
            }
            if (idx >= seen.length) {
                seen = java.util.Arrays.copyOf(seen, idx + 1);
            }
            seen[idx] = true;
            states.get(idx).observe(w, settings.detector());
        }
        int known = topology.size();
        int reporting = 0;
        for (int i = 0; i < known; i++) {
            if (i < seen.length && seen[i]) {
                reporting++;
            } else {
                states.get(i).silent();
            }
        }
        windowsProcessed++;
        lastWindowEnd = windowEnd;
        if (windowsProcessed > 1 && reporting < settings.minObservedShare() * known) {
            gapWindows++;
            return new Views.WindowReport(windowEnd, true, reporting, 0, 0, List.of(), List.of(), elapsed(t0));
        }

        byte[] state = new byte[known];
        boolean[] blackout = new boolean[known];
        Set<Element> candidateElements = new LinkedHashSet<>();
        for (int i = 0; i < known; i++) {
            Health h = states.get(i).health();
            state[i] = switch (h) {
                case DEGRADED -> Localizer.DEGRADED;
                case HEALTHY -> Localizer.HEALTHY;
                case UNKNOWN -> Localizer.EXCLUDED;
            };
            if (h == Health.DEGRADED) {
                candidateElements.addAll(Topology.elementsOf(topology.tunnel(i)));
                blackout[i] = (states.get(i).flags() & Symptom.BLACKOUT) != 0;
            }
        }

        List<Localizer.Candidate> candidates = new ArrayList<>();
        for (Element e : candidateElements) {
            candidates.add(candidate(e, topology.cover(e)));
        }
        Map<Element, ChangeGroup> activeChanges = activeChanges(windowEnd);
        for (Map.Entry<Element, ChangeGroup> en : activeChanges.entrySet()) {
            int[] cover = changeCover(en.getValue(), windowEnd, state);
            if (cover != null) {
                candidates.add(candidate(en.getKey(), cover));
            }
        }

        Set<Element> active = new HashSet<>();
        tracker.openIncidents().forEach(i -> active.add(Element.parse(i.rootCause())));
        double eps = Math.min(0.05, Math.max(settings.localizer().eps(), noise));
        Map<Element.Kind, Integer> population = topology.population();
        population.put(Element.Kind.CHANGE, Math.max(1, activeChanges.size()));
        localizer = new Localizer(settings.localizer().withEps(eps), active, population);
        Localizer.Result result = localizer.localize(state, blackout, candidates);
        if (result.observed() > 0) {
            // background rate: degradation no cause explains, smoothed over a few minutes
            noiseWindows++;
            noise += Math.max(0.03, 1.0 / noiseWindows) * ((double) result.unexplained() / result.observed() - noise);
        }
        WindowView view = new WindowView(windowEnd, topology, state, List.copyOf(states), activeChanges, Map.copyOf(devices));
        List<String> opened = tracker.update(result, view);
        if (listener != null) {
            listener.onWindow(view, candidates, result);
        }
        lastState = state;
        lastResult = result;
        lastChanges = activeChanges;

        double ms = elapsed(t0);
        totalProcessMs += ms;
        maxProcessMs = Math.max(maxProcessMs, ms);
        return new Views.WindowReport(windowEnd, false, result.observed(), result.degraded(), result.unexplained(),
                result.causes().stream().map(c -> c.fit().element().id()).toList(), opened, ms);
    }

    /**
     * Elements that span transports get a per-transport group so the localizer can
     * estimate how visible the fault is on each.
     */
    private Localizer.Candidate candidate(Element e, int[] cover) {
        boolean multi = e.kind() == Element.Kind.SITE || e.kind() == Element.Kind.HUB
                || e.kind() == Element.Kind.CHANGE && e.color() == null;
        if (!multi) {
            return new Localizer.Candidate(e, cover);
        }
        List<String> colors = List.copyOf(topology.colors());
        int[] groups = new int[cover.length];
        for (int k = 0; k < cover.length; k++) {
            groups[k] = colors.indexOf(topology.tunnel(cover[k]).color());
        }
        return new Localizer.Candidate(e, cover, groups);
    }

    /** Rollouts recent enough to be suspects at {@code windowEnd}. */
    private Map<Element, ChangeGroup> activeChanges(long windowEnd) {
        Map<Element, ChangeGroup> out = new LinkedHashMap<>();
        for (ChangeGroup g : changeGroups.values()) {
            if (g.firstApplied() <= windowEnd && g.lastApplied() >= windowEnd - settings.changeLookbackMs()) {
                out.put(g.element(), g);
            }
        }
        return out;
    }

    /**
     * Tunnels a rollout could have broken by now: those of routers that applied it. A
     * rollout is dropped as a suspect when many of its degraded tunnels were already
     * degraded before their router applied it, since a cause can't come after its effect.
     */
    private int[] changeCover(ChangeGroup g, long windowEnd, byte[] state) {
        Map<String, Long> applied = g.appliedBy(windowEnd);
        int[] cover = topology.coverOfRouters(applied.keySet(), g.color());
        int degraded = 0;
        int before = 0;
        for (int i : cover) {
            if (state[i] == Localizer.DEGRADED) {
                degraded++;
                long since = states.get(i).degradedSince();
                Long at = applied.get(topology.tunnel(i).systemIp());
                if (since > 0 && at != null && since < at) {
                    before++;
                }
            }
        }
        if (cover.length == 0 || degraded > 0 && before * 10 > degraded * 3) {
            return null;
        }
        return cover;
    }

    // ---- queries ----------------------------------------------------------------------------

    public synchronized List<IncidentView> incidents(boolean includeResolved) {
        return includeResolved ? tracker.all() : tracker.openIncidents();
    }

    public synchronized Optional<IncidentView> incident(String id) {
        return tracker.find(id);
    }

    public synchronized Views.TunnelQuery tunnels(Views.TunnelFilter f, int limit) {
        List<Views.TunnelView> out = new ArrayList<>();
        int matched = 0;
        int degraded = 0;
        int healthy = 0;
        int unknown = 0;
        for (int i = 0; i < topology.size(); i++) {
            Tunnel t = topology.tunnel(i);
            TunnelState s = states.get(i);
            if (f.siteId() != null && f.siteId() != t.siteId()
                    || f.color() != null && !f.color().equals(t.color())
                    || f.region() != null && !f.region().equals(t.region())
                    || f.hub() != null && !f.hub().equals(t.hub())
                    || Boolean.TRUE.equals(f.degradedOnly()) && s.health() != Health.DEGRADED) {
                continue;
            }
            matched++;
            switch (s.health()) {
                case DEGRADED -> degraded++;
                case HEALTHY -> healthy++;
                case UNKNOWN -> unknown++;
            }
            if (out.size() < limit) {
                out.add(view(t, s));
            }
        }
        out.sort((a, b) -> Boolean.compare("DEGRADED".equals(b.health()), "DEGRADED".equals(a.health())));
        return new Views.TunnelQuery(matched, degraded, healthy, unknown, out, matched > out.size());
    }

    private static Views.TunnelView view(Tunnel t, TunnelState s) {
        return new Views.TunnelView(t.key(), t.siteId(), t.systemIp(), t.region(), t.color(), t.hub(), s.health().name(),
                Symptom.describe(s.flags()), round(s.lossPct()), round(s.baselineLossRate() * 100),
                s.latencyMs() == null ? null : round(s.latencyMs()), round(s.baselineLatencyMs()),
                s.jitterMs() == null ? null : round(s.jitterMs()), round(s.baselineJitterMs()),
                s.degradedSince() > 0 ? s.degradedSince() : null);
    }

    public synchronized List<Views.ChangeView> changes(long sinceMs) {
        List<Views.ChangeView> out = new ArrayList<>();
        long now = lastWindowEnd == null ? Long.MAX_VALUE : lastWindowEnd;
        for (ChangeGroup g : changeGroups.values()) {
            if (g.lastApplied() < sinceMs) {
                continue;
            }
            int[] cover = topology.coverOfRouters(g.appliedBy(now).keySet(), g.color());
            int obs = 0;
            int bad = 0;
            for (int i : cover) {
                if (i < lastState.length && lastState[i] != Localizer.EXCLUDED) {
                    obs++;
                    if (lastState[i] == Localizer.DEGRADED) {
                        bad++;
                    }
                }
            }
            out.add(new Views.ChangeView(g.element().id(), g.deploymentId(), g.label(), g.color(), g.routers(),
                    g.firstApplied(), g.lastApplied(), g.diff(), bad, obs));
        }
        return out;
    }

    public synchronized Views.Grid grid() {
        List<String> regions = List.copyOf(topology.regions());
        List<String> colors = List.copyOf(topology.colors());
        List<Views.Cell> cells = new ArrayList<>();
        for (String r : regions) {
            for (String c : colors) {
                int[] k = count(topology.cover(Element.provider(c, r)));
                if (k[0] + k[2] > 0) {
                    cells.add(new Views.Cell(r, c, k[0], k[1]));
                }
            }
        }
        List<Views.HubCell> hubs = new ArrayList<>();
        for (String h : topology.hubs()) {
            for (String c : colors) {
                int[] k = count(topology.cover(Element.hubCircuit(h, c)));
                if (k[0] + k[2] > 0) {
                    hubs.add(new Views.HubCell(h, c, k[0], k[1]));
                }
            }
        }
        return new Views.Grid(lastWindowEnd == null ? 0 : lastWindowEnd, regions, colors, cells, hubs);
    }

    /** [observed, degraded, total] in the latest window. */
    private int[] count(int[] cover) {
        int obs = 0;
        int bad = 0;
        for (int i : cover) {
            if (i < lastState.length && lastState[i] != Localizer.EXCLUDED) {
                obs++;
                if (lastState[i] == Localizer.DEGRADED) {
                    bad++;
                }
            }
        }
        return new int[] {obs, bad, cover.length};
    }

    /** Explains why an incident's root cause beats another element, on the latest window. */
    public synchronized Views.Comparison compare(String incidentId, String elementId) {
        IncidentView inc = tracker.find(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("no incident " + incidentId));
        Element alt = Element.parse(elementId);
        Element root = Element.parse(inc.rootCause());
        WindowView view = new WindowView(lastWindowEnd == null ? 0 : lastWindowEnd, topology, lastState,
                List.copyOf(states), lastChanges, Map.of());
        int[] rootCover = view.cover(root);
        int[] altCover = view.cover(alt);
        int[] rc = view.count(rootCover);
        int[] ac = view.count(altCover);
        Set<Integer> rootHits = new HashSet<>();
        for (int i : rootCover) {
            if (view.degraded(i)) {
                rootHits.add(i);
            }
        }
        int overlap = 0;
        for (int i : altCover) {
            if (rootHits.contains(i)) {
                overlap++;
            }
        }
        List<Localizer.Fit> chosen = lastResult.causes().stream().map(Localizer.Cause::fit).toList();
        Localizer.Fit rootFit = chosen.stream().filter(f -> f.element().equals(root)).findFirst().orElse(null);
        if (altCover.length == 0) {
            return new Views.Comparison(incidentId, root.id(), rc[0], rc[1], alt.id(), 0, 0, 0, null,
                    "No tunnels depend on " + alt.id() + " in the current topology.");
        }
        if (rootFit == null) {
            return new Views.Comparison(incidentId, root.id(), rc[0], rc[1], alt.id(), ac[0], ac[1], overlap, null,
                    "The incident is not active in the latest window, so only the counts are compared.");
        }
        double margin = localizer.swapMargin(lastState, chosen, rootFit, candidate(alt, altCover));
        String verdict;
        if (chosen.stream().anyMatch(f -> f.element().equals(alt))) {
            verdict = alt.id() + " is itself part of the current explanation, as a separate cause.";
        } else if (margin < 1) {
            verdict = "The two fit the data about as well; telemetry alone can't separate them.";
        } else if (margin < 4) {
            verdict = String.format("%s fits better, but only slightly (%.1f nats).", root.id(), margin);
        } else {
            verdict = String.format("%s fits much better (%.1f nats): %s.", root.id(), margin,
                    overlap < rc[1] ? String.format("%s covers only %d of the %d degraded tunnels", alt.id(), overlap, rc[1])
                            : String.format("%s also predicts trouble on %d tunnels that are fine", alt.id(), ac[0] - ac[1]));
        }
        return new Views.Comparison(incidentId, root.id(), rc[0], rc[1], alt.id(), ac[0], ac[1], overlap,
                Math.round(margin * 10) / 10.0, verdict);
    }

    public synchronized Views.Status status() {
        return new Views.Status(windowsProcessed, gapWindows, lastWindowEnd, topology.size(), lastResult.observed(),
                lastResult.degraded(), lastResult.unexplained(), tracker.openIncidents().size(),
                windowsProcessed == 0 ? 0 : round(totalProcessMs / Math.max(1, windowsProcessed - gapWindows)),
                round(maxProcessMs), changeGroups.size(), devices.size(),
                Math.round(Math.max(settings.localizer().eps(), noise) * 10000) / 100.0);
    }

    private static double elapsed(long t0) {
        return (System.nanoTime() - t0) / 1e6;
    }

    private static double round(double x) {
        return Math.round(x * 100) / 100.0;
    }
}
