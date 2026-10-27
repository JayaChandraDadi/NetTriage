package io.nettriage.core.incident;

import io.nettriage.core.WindowView;
import io.nettriage.core.explain.EvidenceBuilder;
import io.nettriage.core.explain.Explanation;
import io.nettriage.core.localize.Localizer;
import io.nettriage.core.model.Element;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns per-window explanations into incidents with a lifetime.
 *
 * <p>A cause seen in a window joins the open incident with the same root cause. If none
 * matches, it joins an open incident that explains mostly the same tunnels: the
 * explanation sharpened (one circuit at a site, then the whole site) and the incident
 * keeps its id while recording the change. "Mostly the same" means most of both sets:
 * a one-site outage whose two tunnels to a hub overlap a fading hub incident is a new
 * incident, not a relabeled one. Otherwise it may open a new incident: at once
 * when the cause is clear (high confidence), or once the same cause has been seen in two
 * consecutive windows. Two unrelated blips that happen to land on one site in the same
 * window look like a weak site problem for one window; they don't become a page.
 * An incident not explained for {@code resolveAfter} consecutive windows is resolved.
 */
public final class IncidentTracker {

    private final int resolveAfter;
    private final int timelineCap;
    private final int retainResolved;
    private final Map<String, Incident> open = new LinkedHashMap<>();
    private final List<Incident> resolved = new ArrayList<>();
    private Set<Element> seenLastWindow = Set.of();
    private int nextId = 1;

    public IncidentTracker(int resolveAfter, int timelineCap, int retainResolved) {
        this.resolveAfter = resolveAfter;
        this.timelineCap = timelineCap;
        this.retainResolved = retainResolved;
    }

    /** Applies one window's explanation; returns ids of incidents opened in this window. */
    public List<String> update(Localizer.Result result, WindowView view) {
        long now = view.windowEnd();
        Set<Incident> matched = new HashSet<>();
        List<String> opened = new ArrayList<>();
        List<Localizer.Cause> pending = new ArrayList<>();

        for (Localizer.Cause c : result.causes()) {
            Incident inc = byRoot(c.fit().element());
            if (inc != null && !matched.contains(inc)) {
                inc.observe(c, view, now, timelineCap);
                matched.add(inc);
            } else {
                pending.add(c);
            }
        }
        for (Localizer.Cause c : pending) {
            Set<Integer> hits = hits(c, view);
            Incident best = null;
            double bestOverlap = 0.5;
            for (Incident inc : open.values()) {
                if (matched.contains(inc)) {
                    continue;
                }
                double ov = overlap(hits, inc.lastHits);
                if (ov >= bestOverlap) {
                    best = inc;
                    bestOverlap = ov;
                }
            }
            if (best == null) {
                boolean persistent = seenLastWindow.contains(c.fit().element());
                if (c.confidence() != Localizer.Confidence.HIGH && !persistent) {
                    continue;
                }
                best = new Incident(String.format("INC-%04d", nextId++), now);
                open.put(best.id, best);
                opened.add(best.id);
            }
            best.observe(c, view, now, timelineCap);
            matched.add(best);
        }
        Set<Element> seen = new HashSet<>();
        result.causes().forEach(c -> seen.add(c.fit().element()));
        seenLastWindow = seen;
        for (Incident inc : List.copyOf(open.values())) {
            if (!matched.contains(inc)) {
                inc.quiet++;
                inc.point(now, 0, timelineCap);
                if (inc.quiet >= resolveAfter) {
                    inc.resolvedAt = now;
                    open.remove(inc.id);
                    resolved.add(0, inc);
                    if (resolved.size() > retainResolved) {
                        resolved.remove(resolved.size() - 1);
                    }
                }
            }
        }
        return opened;
    }

    public List<IncidentView> openIncidents() {
        return open.values().stream().map(Incident::view).toList();
    }

    public List<IncidentView> all() {
        List<IncidentView> out = new ArrayList<>(openIncidents());
        resolved.forEach(i -> out.add(i.view()));
        return out;
    }

    public Optional<IncidentView> find(String id) {
        Incident i = open.get(id);
        if (i != null) {
            return Optional.of(i.view());
        }
        return resolved.stream().filter(r -> r.id.equals(id)).findFirst().map(Incident::view);
    }

    private Incident byRoot(Element e) {
        for (Incident inc : open.values()) {
            if (e.equals(inc.root)) {
                return inc;
            }
        }
        return null;
    }

    private static Set<Integer> hits(Localizer.Cause c, WindowView v) {
        Set<Integer> s = new HashSet<>();
        for (int i : c.fit().cover()) {
            if (v.degraded(i)) {
                s.add(i);
            }
        }
        return s;
    }

    private static double overlap(Set<Integer> a, Set<Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (int i : a) {
            if (b.contains(i)) {
                common++;
            }
        }
        return (double) common / Math.max(a.size(), b.size());
    }

    /** Mutable incident state, confined to the tracker. */
    private static final class Incident {
        final String id;
        final long openedAt;
        Element root;
        Localizer.Cause cause;
        Explanation explanation;
        Explanation peakExplanation;
        Localizer.Cause peakCause;
        Set<Integer> lastHits = new HashSet<>();
        final Set<Integer> sites = new TreeSet<>();
        final List<IncidentView.RootCauseChange> history = new ArrayList<>();
        final List<IncidentView.Point> timeline = new ArrayList<>();
        long onsetAt = Long.MAX_VALUE;
        long lastSeenAt;
        Long resolvedAt;
        int peak;
        int quiet;

        Incident(String id, long openedAt) {
            this.id = id;
            this.openedAt = openedAt;
        }

        void observe(Localizer.Cause c, WindowView v, long now, int cap) {
            if (!c.fit().element().equals(root)) {
                root = c.fit().element();
                history.add(new IncidentView.RootCauseChange(now, root.id()));
            }
            cause = c;
            explanation = EvidenceBuilder.explain(c, v);
            lastHits = hits(c, v);
            sites.addAll(explanation.sites());
            onsetAt = Math.min(onsetAt, explanation.onsetAt());
            lastSeenAt = now;
            if (c.fit().hits() > peak) {
                // what the incident looked like at its worst; a resolved incident is shown
                // this way, not as its last fading window
                peakExplanation = explanation;
                peakCause = c;
            }
            peak = Math.max(peak, c.fit().hits());
            quiet = 0;
            point(now, c.fit().hits(), cap);
        }

        void point(long at, int degraded, int cap) {
            timeline.add(new IncidentView.Point(at, degraded));
            if (timeline.size() > cap) {
                timeline.remove(0);
            }
        }

        IncidentView view() {
            boolean isOpen = resolvedAt == null;
            Explanation explanation = isOpen || peakExplanation == null ? this.explanation : peakExplanation;
            Localizer.Cause cause = isOpen || peakCause == null ? this.cause : peakCause;
            Localizer.Fit f = cause.fit();
            return new IncidentView(id, isOpen ? "OPEN" : "RESOLVED", root.id(), root.kind().name(), root.describe(),
                    explanation.headline(), cause.confidence().name(), Math.round(cause.margin() * 10) / 10.0,
                    isOpen && quiet == 0 ? f.hits() : 0, f.covered(), Math.round(f.p() * 1000) / 1000.0, peak,
                    sites.size(), List.copyOf(sites), onsetAt, openedAt, lastSeenAt, resolvedAt,
                    explanation.signature(), explanation.evidence(), explanation.alternatives(), explanation.actions(),
                    List.copyOf(history), List.copyOf(timeline));
        }
    }
}
