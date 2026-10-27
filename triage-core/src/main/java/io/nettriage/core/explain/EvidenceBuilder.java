package io.nettriage.core.explain;

import io.nettriage.core.ChangeGroup;
import io.nettriage.core.DeviceStatus;
import io.nettriage.core.WindowView;
import io.nettriage.core.detect.Symptom;
import io.nettriage.core.detect.TunnelState;
import io.nettriage.core.localize.Localizer;
import io.nettriage.core.model.Element;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a localized cause into sentences an on-call engineer can check: what is broken,
 * what is not (which is how the alternatives were ruled out), what it looks like, when
 * it started, and what to do. Every number comes from the window being explained.
 */
public final class EvidenceBuilder {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final WindowView v;
    private final List<Evidence> evidence = new ArrayList<>();

    private EvidenceBuilder(WindowView v) {
        this.v = v;
    }

    public static Explanation explain(Localizer.Cause cause, WindowView v) {
        return new EvidenceBuilder(v).build(cause);
    }

    private Explanation build(Localizer.Cause cause) {
        Localizer.Fit fit = cause.fit();
        Element e = fit.element();
        int[] cover = fit.cover();
        List<Integer> hit = new ArrayList<>();
        Set<Integer> sites = new TreeSet<>();
        for (int i : cover) {
            if (v.degraded(i)) {
                hit.add(i);
                sites.add(v.tunnel(i).siteId());
            }
        }
        Signature sig = signature(hit);
        long onset = Long.MAX_VALUE;
        long lastOnset = Long.MIN_VALUE;
        for (int i : hit) {
            long s = v.tunnels().get(i).degradedSince();
            if (s > 0) {
                onset = Math.min(onset, s);
                lastOnset = Math.max(lastOnset, s);
            }
        }
        if (onset == Long.MAX_VALUE) {
            onset = v.windowEnd();
            lastOnset = onset;
        }

        switch (e.kind()) {
            case PROVIDER -> provider(e, cover);
            case CARRIER -> carrier(e, cover);
            case CIRCUIT -> circuit(e, cover);
            case SITE -> site(e, cover);
            case HUB -> hub(e, cover);
            case HUB_CIRCUIT -> hubCircuit(e, cover);
            case CHANGE -> change(e, cover);
        }
        add(sig.describe(), Map.of("kind", sig.kind(), "lossPct", round1(sig.lossPct()),
                "latencyRiseMs", Math.round(sig.latencyRiseMs()), "jitterRiseMs", Math.round(sig.jitterRiseMs())));
        if (sites.size() > 1) {
            add(String.format("The first affected tunnel was flagged at %s and the last of the %d sites %d s later.",
                    CLOCK.format(Instant.ofEpochMilli(onset)), sites.size(), (lastOnset - onset) / 1000),
                    Map.of("onset", onset, "spreadMs", lastOnset - onset));
        } else {
            add(String.format("It was first flagged at %s.", CLOCK.format(Instant.ofEpochMilli(onset))), Map.of("onset", onset));
        }
        if (e.kind() != Element.Kind.CHANGE) {
            exonerateChanges(fit, sites);
        }

        List<AlternativeView> alts = new ArrayList<>();
        for (Localizer.Alternative a : cause.alternatives()) {
            alts.add(new AlternativeView(a.element().id(), a.element().describe(), a.covered(), a.hits(), a.overlap(),
                    round1(a.margin()), reason(fit, a)));
        }
        return new Explanation(headline(e, fit, sites, sig), sig, List.copyOf(evidence), alts,
                actions(e, sig, sites), Collections.unmodifiableSet(sites), onset);
    }

    // ---- per kind -------------------------------------------------------------------------

    private void provider(Element e, int[] cover) {
        int[] own = v.count(cover);
        add(String.format("%s of %s %s tunnels in %s are degraded.", own[1], own[0], e.color(), e.region()),
                Map.of("degraded", own[1], "observed", own[0]));
        List<String> elsewhere = new ArrayList<>();
        for (String r : v.topology().regions()) {
            if (r.equals(e.region())) {
                continue;
            }
            int[] c = v.count(v.topology().cover(Element.provider(e.color(), r)));
            if (c[0] > 0) {
                elsewhere.add(String.format("%s %d of %d", r, c[1], c[0]));
            }
        }
        if (!elsewhere.isEmpty()) {
            add(String.format("%s in other regions is fine (%s degraded), so this is not the carrier everywhere.",
                    e.color(), String.join(", ", elsewhere)), Map.of());
        }
        List<String> others = otherColorsIn(e.region(), e.color());
        if (!others.isEmpty()) {
            add(String.format("Other transports in %s are fine (%s degraded), so the sites themselves are up.",
                    e.region(), String.join(", ", others)), Map.of());
        }
        hubSplit(cover, "neither hub is the cause");
    }

    private void carrier(Element e, int[] cover) {
        int[] own = v.count(cover);
        List<String> parts = new ArrayList<>();
        for (String r : v.topology().regions()) {
            int[] c = v.count(v.topology().cover(Element.provider(e.color(), r)));
            if (c[0] > 0) {
                parts.add(String.format("%s %d of %d", r, c[1], c[0]));
            }
        }
        add(String.format("%d of %d %s tunnels are degraded, in every region (%s).", own[1], own[0], e.color(),
                String.join(", ", parts)), Map.of("degraded", own[1], "observed", own[0]));
        List<String> others = new ArrayList<>();
        for (String c : v.topology().colors()) {
            if (!c.equals(e.color())) {
                int[] k = v.count(v.topology().cover(Element.carrier(c)));
                others.add(String.format("%s %d of %d", c, k[1], k[0]));
            }
        }
        if (!others.isEmpty()) {
            add(String.format("Other transports are fine (%s degraded).", String.join(", ", others)), Map.of());
        }
        hubSplit(cover, "neither hub is the cause");
    }

    private void circuit(Element e, int[] cover) {
        int[] own = v.count(cover);
        add(String.format("%d of %d %s tunnels from site %d are degraded.", own[1], own[0], e.color(), e.siteId()),
                Map.of("degraded", own[1], "observed", own[0]));
        List<String> others = new ArrayList<>();
        for (String c : v.topology().colors()) {
            if (c.equals(e.color())) {
                continue;
            }
            int[] k = v.count(v.topology().cover(Element.circuit(e.siteId(), c)));
            if (k[0] > 0) {
                others.add(String.format("%s %d of %d", c, k[1], k[0]));
            }
        }
        if (!others.isEmpty()) {
            add(String.format("Site %d's other transports are fine (%s degraded), so the router is up.", e.siteId(),
                    String.join(", ", others)), Map.of());
        } else {
            add(String.format("Site %d has no other transport to compare with, so a router failure can't be ruled out.",
                    e.siteId()), Map.of());
        }
        String region = v.topology().regionOfSite(e.siteId());
        int[] peers = v.count(v.topology().cover(Element.provider(e.color(), region)),
                i -> v.tunnel(i).siteId() != e.siteId());
        if (peers[0] > 0) {
            add(String.format("Other %s sites in %s are fine (%d of %d tunnels degraded), so the provider is not the cause.",
                    e.color(), region, peers[1], peers[0]), Map.of("peerDegraded", peers[1], "peerObserved", peers[0]));
        }
        session(e.siteId());
    }

    private void site(Element e, int[] cover) {
        int[] own = v.count(cover);
        List<String> parts = new ArrayList<>();
        for (String c : v.topology().colors()) {
            int[] k = v.count(v.topology().cover(Element.circuit(e.siteId(), c)));
            if (k[0] > 0) {
                parts.add(String.format("%s %d of %d", c, k[1], k[0]));
            }
        }
        add(String.format("%d of %d tunnels from site %d are degraded, on every transport (%s).", own[1], own[0],
                e.siteId(), String.join(", ", parts)), Map.of("degraded", own[1], "observed", own[0]));
        String region = v.topology().regionOfSite(e.siteId());
        int[] peers = v.count(allIn(region), i -> v.tunnel(i).siteId() != e.siteId());
        add(String.format("Other sites in %s are fine (%d of %d tunnels degraded), so no provider or hub is the cause.",
                region, peers[1], peers[0]), Map.of("peerDegraded", peers[1], "peerObserved", peers[0]));
        session(e.siteId());
    }

    private void hub(Element e, int[] cover) {
        int[] own = v.count(cover);
        List<String> parts = new ArrayList<>();
        for (String c : v.topology().colors()) {
            int[] k = v.count(v.topology().cover(Element.hubCircuit(e.hub(), c)));
            if (k[0] > 0) {
                parts.add(String.format("%s %d of %d", c, k[1], k[0]));
            }
        }
        add(String.format("%d of %d tunnels ending at hub %s are degraded, across transports (%s).", own[1], own[0],
                e.hub(), String.join(", ", parts)), Map.of("degraded", own[1], "observed", own[0]));
        for (String h : v.topology().hubs()) {
            if (!h.equals(e.hub())) {
                int[] k = v.count(v.topology().cover(Element.hub(h)));
                add(String.format("The same sites' tunnels to hub %s are fine (%d of %d degraded), so the branches and "
                        + "their providers are up.", h, k[1], k[0]), Map.of("otherHubDegraded", k[1], "otherHubObserved", k[0]));
            }
        }
        regionsSpread(cover);
    }

    private void hubCircuit(Element e, int[] cover) {
        int[] own = v.count(cover);
        add(String.format("%d of %d %s tunnels ending at hub %s are degraded.", own[1], own[0], e.color(), e.hub()),
                Map.of("degraded", own[1], "observed", own[0]));
        List<String> others = new ArrayList<>();
        for (String c : v.topology().colors()) {
            if (!c.equals(e.color())) {
                int[] k = v.count(v.topology().cover(Element.hubCircuit(e.hub(), c)));
                if (k[0] > 0) {
                    others.add(String.format("%s %d of %d", c, k[1], k[0]));
                }
            }
        }
        if (!others.isEmpty()) {
            add(String.format("Hub %s's other transports are fine (%s degraded), so the hub router is up.", e.hub(),
                    String.join(", ", others)), Map.of());
        }
        for (String h : v.topology().hubs()) {
            if (!h.equals(e.hub())) {
                int[] k = v.count(v.topology().cover(Element.hubCircuit(h, e.color())));
                add(String.format("%s tunnels to hub %s are fine (%d of %d degraded), so the branch-side %s providers are up.",
                        e.color(), h, k[1], k[0], e.color()), Map.of());
            }
        }
        regionsSpread(cover);
    }

    private void change(Element e, int[] cover) {
        ChangeGroup g = v.changes().get(e);
        if (g == null) {
            return;
        }
        Map<String, Long> applied = g.appliedBy(v.windowEnd());
        int[] own = v.count(cover);
        String scope = e.color() == null ? "tunnels" : e.color() + " tunnels";
        add(String.format("%d routers applied %s between %s and %s; %d of their %d %s are degraded.", applied.size(),
                g.label(), CLOCK.format(Instant.ofEpochMilli(g.firstApplied())),
                CLOCK.format(Instant.ofEpochMilli(Math.min(g.lastApplied(), v.windowEnd()))), own[1], own[0], scope),
                Map.of("routers", applied.size(), "degraded", own[1], "observed", own[0]));

        int[] rest = v.count(e.color() == null ? allTunnels() : v.topology().cover(Element.carrier(e.color())),
                i -> !applied.containsKey(v.tunnel(i).systemIp()));
        add(String.format("Routers that have not applied it are fine: %d of %d of their %s degraded.", rest[1], rest[0], scope),
                Map.of("unchangedDegraded", rest[1], "unchangedObserved", rest[0]));

        // timing: did each router degrade after it applied the change?
        List<Long> lags = new ArrayList<>();
        int before = 0;
        for (Map.Entry<String, Long> a : applied.entrySet()) {
            long first = Long.MAX_VALUE;
            for (int i : v.topology().tunnelsOfRouter(a.getKey())) {
                if (v.degraded(i) && (e.color() == null || e.color().equals(v.tunnel(i).color()))) {
                    first = Math.min(first, v.tunnels().get(i).degradedSince());
                }
            }
            if (first == Long.MAX_VALUE) {
                continue;
            }
            if (first < a.getValue()) {
                before++;
            } else {
                lags.add(first - a.getValue());
            }
        }
        if (!lags.isEmpty()) {
            Collections.sort(lags);
            add(String.format("Each affected router degraded after it applied the change (median %d s later)%s.",
                    lags.get(lags.size() / 2) / 1000, before == 0 ? ", never before" : "; " + before + " degraded before"),
                    Map.of("medianLagMs", lags.get(lags.size() / 2), "degradedBeforeApply", before));
        }
        Set<String> regions = new TreeSet<>();
        Set<Integer> sites = new TreeSet<>();
        for (int i : cover) {
            if (v.degraded(i)) {
                regions.add(v.tunnel(i).region());
                sites.add(v.tunnel(i).siteId());
            }
        }
        if (sites.size() > 1) {
            add(String.format("The affected sites are spread over %s, so no shared provider, site or hub explains them.",
                    String.join(", ", regions)), Map.of("regions", List.copyOf(regions), "sites", sites.size()));
        }
        if (!g.diff().isEmpty()) {
            add("What changed: " + String.join("; ", g.diff().subList(0, Math.min(5, g.diff().size()))) + ".",
                    Map.of("diff", g.diff()));
        }
    }

    // ---- shared pieces --------------------------------------------------------------------

    private void hubSplit(int[] cover, String conclusion) {
        Map<String, int[]> byHub = new LinkedHashMap<>();
        for (String h : v.topology().hubs()) {
            int[] k = v.count(cover, i -> v.tunnel(i).hub().equals(h));
            if (k[0] > 0) {
                byHub.put(h, k);
            }
        }
        if (byHub.size() > 1 && byHub.values().stream().allMatch(k -> k[1] > 0)) {
            List<String> parts = new ArrayList<>();
            byHub.forEach((h, k) -> parts.add(String.format("%d of %d to %s", k[1], k[0], h)));
            add(String.format("Both hubs see it (%s), so %s.", String.join(", ", parts), conclusion), Map.of());
        }
    }

    private void regionsSpread(int[] cover) {
        Set<String> regions = new TreeSet<>();
        for (int i : cover) {
            if (v.degraded(i)) {
                regions.add(v.tunnel(i).region());
            }
        }
        if (regions.size() > 1) {
            add(String.format("Affected branches are in %s, so no regional provider explains it.",
                    String.join(", ", regions)), Map.of("regions", List.copyOf(regions)));
        }
    }

    private void session(int siteId) {
        for (DeviceStatus d : v.devices().values()) {
            if (siteOf(d.systemIp()) == siteId) {
                add(d.connected()
                        ? String.format("EdgeFleet still holds a control session with this router (since %s).",
                                CLOCK.format(Instant.ofEpochMilli(d.since())))
                        : String.format("EdgeFleet lost its control session with this router at %s.",
                                CLOCK.format(Instant.ofEpochMilli(d.since()))),
                        Map.of("connected", d.connected(), "since", d.since()));
                return;
            }
        }
    }

    private int siteOf(String systemIp) {
        List<Integer> t = v.topology().tunnelsOfRouter(systemIp);
        return t.isEmpty() ? -1 : v.tunnel(t.get(0)).siteId();
    }

    /** A rollout that touched the affected routers but does not fit the pattern. */
    private void exonerateChanges(Localizer.Fit fit, Set<Integer> sites) {
        for (Map.Entry<Element, ChangeGroup> en : v.changes().entrySet()) {
            ChangeGroup g = en.getValue();
            Map<String, Long> applied = g.appliedBy(v.windowEnd());
            long touching = applied.keySet().stream().filter(ip -> sites.contains(siteOf(ip))).count();
            if (touching == 0) {
                continue;
            }
            int[] k = v.count(v.cover(en.getKey()));
            add(String.format("Config rollout %s reached %d routers recently, %d of them at affected sites, but only "
                            + "%d of its %d %s are degraded, against %d of %d for the %s. The rollout does not explain the pattern.",
                    g.label(), applied.size(), touching, k[1], k[0],
                    g.color() == null ? "tunnels" : g.color() + " tunnels", fit.hits(), fit.covered(),
                    fit.element().describe()),
                    Map.of("element", en.getKey().id(), "degraded", k[1], "observed", k[0]));
        }
    }

    private List<String> otherColorsIn(String region, String color) {
        List<String> out = new ArrayList<>();
        for (String c : v.topology().colors()) {
            if (!c.equals(color)) {
                int[] k = v.count(v.topology().cover(Element.provider(c, region)));
                if (k[0] > 0) {
                    out.add(String.format("%s %d of %d", c, k[1], k[0]));
                }
            }
        }
        return out;
    }

    private int[] allIn(String region) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < v.topology().size(); i++) {
            if (v.tunnel(i).region().equals(region)) {
                out.add(i);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private int[] allTunnels() {
        int[] all = new int[v.topology().size()];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return all;
    }

    private Signature signature(List<Integer> hit) {
        if (hit.isEmpty()) {
            return new Signature("none", 0, 0, 0, 0, 0, 0);
        }
        List<Double> loss = new ArrayList<>();
        List<Double> base = new ArrayList<>();
        List<Double> lat = new ArrayList<>();
        List<Double> jit = new ArrayList<>();
        int blackouts = 0;
        for (int i : hit) {
            TunnelState s = v.tunnels().get(i);
            loss.add(s.lossPct());
            base.add(s.baselineLossRate() * 100);
            if ((s.flags() & Symptom.BLACKOUT) != 0) {
                blackouts++;
            }
            if (s.latencyMs() != null) {
                lat.add(s.latencyMs() - s.baselineLatencyMs());
            }
            if (s.jitterMs() != null) {
                jit.add(s.jitterMs() - s.baselineJitterMs());
            }
        }
        double share = (double) blackouts / hit.size();
        double mLoss = median(loss);
        double mBase = median(base);
        double mLat = median(lat);
        double mJit = median(jit);
        String kind;
        if (share >= 0.5) {
            kind = "blackout";
        } else {
            List<String> parts = new ArrayList<>();
            if (mLoss - mBase >= 1.0) {
                parts.add("loss");
            }
            if (mLat >= 15) {
                parts.add("latency");
            }
            if (mJit >= 8) {
                parts.add("jitter");
            }
            kind = parts.isEmpty() ? "mixed" : String.join("+", parts);
        }
        return new Signature(kind, mLoss, mBase, mLat, mJit, share, hit.size());
    }

    private static double median(List<Double> xs) {
        if (xs.isEmpty()) {
            return 0;
        }
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private static String reason(Localizer.Fit root, Localizer.Alternative a) {
        if (a.margin() < 1) {
            return "Fits the data about as well; telemetry alone can't tell the two apart.";
        }
        List<String> why = new ArrayList<>();
        if (a.overlap() < root.hits()) {
            why.add(String.format("explains only %d of the %d degraded tunnels", a.overlap(), root.hits()));
        }
        int altMisses = a.covered() - a.hits();
        int rootMisses = root.covered() - root.hits();
        if (altMisses > rootMisses) {
            why.add(String.format("predicts trouble on %d tunnels that are fine (%d of %d degraded)", altMisses,
                    a.hits(), a.covered()));
        }
        if (why.isEmpty()) {
            why.add("fits the pattern less well");
        }
        String s = String.join(" and ", why);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".";
    }

    private static String headline(Element e, Localizer.Fit fit, Set<Integer> sites, Signature sig) {
        String what = switch (sig.kind()) {
            case "blackout" -> "down";
            case "jitter" -> "jittery";
            case "latency" -> "slow";
            default -> "degraded";
        };
        String where = sites.size() == 1 ? "1 site" : sites.size() + " sites";
        return switch (e.kind()) {
            case CHANGE -> String.format("Config rollout %s on %s degraded %d of %d tunnels at %s",
                    shortId(e.deploymentId()), e.color() == null ? "all transports" : e.color(), fit.hits(), fit.covered(), where);
            default -> String.format("%s is %s: %d of %d tunnels at %s", capitalize(e.describe()), what, fit.hits(),
                    fit.covered(), where);
        };
    }

    private static List<Action> actions(Element e, Signature sig, Set<Integer> sites) {
        List<Action> out = new ArrayList<>();
        int n = sites.size();
        switch (e.kind()) {
            case PROVIDER -> {
                out.add(new Action("A1", Action.Kind.ADVISORY, "Steer real-time traffic off " + e.color() + " in " + e.region(),
                        "Apply an app-aware routing policy at the " + n + " affected sites that prefers their other "
                                + "transports for voice and video until the provider recovers.", null));
                out.add(new Action("A2", Action.Kind.ADVISORY, "Open a ticket with the " + e.color() + " provider in " + e.region(),
                        n + " sites affected. Attach the evidence above: the regional scope and the signature.", null));
            }
            case CARRIER -> {
                out.add(new Action("A1", Action.Kind.ADVISORY, "Steer real-time traffic off " + e.color() + " everywhere",
                        "Prefer the other transports for voice and video fleet-wide until the carrier recovers.", null));
                out.add(new Action("A2", Action.Kind.ADVISORY, "Escalate to the " + e.color() + " carrier",
                        "The problem spans every region, which points at the carrier's core rather than local access.", null));
            }
            case CIRCUIT -> {
                out.add(new Action("A1", Action.Kind.ADVISORY, "Check the " + e.color() + " circuit at site " + e.siteId(),
                        "blackout".equals(sig.kind())
                                ? "Every probe is lost. Check the modem or handoff on site, then raise a carrier ticket."
                                : "The circuit is degraded, not down. Check for errors on the interface and a saturated uplink.",
                        null));
                out.add(new Action("A2", Action.Kind.ADVISORY, "Confirm traffic failed over",
                        "Application traffic at site " + e.siteId() + " should already be using its other transports.", null));
            }
            case SITE -> out.add(new Action("A1", Action.Kind.ADVISORY, "Check the router at site " + e.siteId(),
                    "Every transport failed together, which points at the router or site power rather than a circuit.", null));
            case HUB -> {
                out.add(new Action("A1", Action.Kind.ADVISORY, "Prefer the other hub",
                        "Spokes still reach the other hub. Lower the preference for hub " + e.hub() + " until it is fixed.", null));
                out.add(new Action("A2", Action.Kind.ADVISORY, "Check hub " + e.hub(),
                        "CPU, crypto throughput and its upstream circuits: every transport into it is affected.", null));
            }
            case HUB_CIRCUIT -> out.add(new Action("A1", Action.Kind.ADVISORY,
                    "Check hub " + e.hub() + "'s " + e.color() + " circuit",
                    "Only " + e.color() + " into this hub is affected; branches on " + e.color() + " still reach the other hub.",
                    null));
            case CHANGE -> {
                if (!e.deploymentId().startsWith("adhoc-")) {
                    out.add(new Action("A1", Action.Kind.ROLLBACK_DEPLOYMENT, "Roll back rollout " + shortId(e.deploymentId()),
                            "Restore the previous config on every router that applied it, through EdgeFleet. "
                                    + "Needs a person to approve.", e.deploymentId()));
                }
                out.add(new Action(out.isEmpty() ? "A1" : "A2", Action.Kind.ADVISORY, "Find what the health check missed",
                        "The routers accepted this config and passed their post-apply health check, yet their tunnels "
                                + "degraded afterwards. Add the failing metric to the check.", null));
            }
        }
        return out;
    }

    private void add(String text, Map<String, Object> data) {
        evidence.add(new Evidence("E" + (evidence.size() + 1), text, data));
    }

    private static String shortId(String id) {
        return id != null && id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Capitalizes a sentence start, but leaves transport names (mpls, lte) as they are written. */
    private static String capitalize(String s) {
        if (s.isEmpty() || s.startsWith("mpls") || s.startsWith("lte") || s.startsWith("biz-internet")) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }

    static String clock(long ms) {
        return CLOCK.format(Instant.ofEpochMilli(ms));
    }
}
