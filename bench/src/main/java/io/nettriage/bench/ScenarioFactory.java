package io.nettriage.bench;

import io.nettriage.core.model.ConfigChange;
import io.nettriage.core.model.Tunnel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Builds benchmark scenarios. Every scenario starts with three minutes of normal
 * traffic so baselines can form, then injects its fault at a random second. Every
 * scenario also carries background noise: single tunnels that drop a third of their
 * probes for 10 to 20 seconds, at random, the way real last miles misbehave.
 */
public final class ScenarioFactory {

    public static final List<String> TYPES = List.of(
            "provider-brownout", "subtle-brownout", "noisy-brownout", "jitter-storm", "carrier-outage", "circuit-blackout", "circuit-brownout",
            "site-down", "hub-degradation", "hub-circuit", "bad-rollout", "red-herring-rollout",
            "compound", "overlapping-outage", "benign-rollout", "noise-only");

    static final long T0 = 1_760_000_000_000L;
    static final int WARMUP_WINDOWS = 18;
    static final long FAULT_MS = 300_000;
    static final long EVAL_DELAY_MS = 90_000;
    static final long TAIL_MS = 120_000;
    static final String[] REGIONS = {"us-west", "us-east", "eu-west"};
    static final String[] COLORS = {"mpls", "biz-internet", "lte"};
    static final String[] HUBS = {SimFleet.WEST_HUB, SimFleet.EAST_HUB};

    private final SimFleet fleet;

    public ScenarioFactory(SimFleet fleet) {
        this.fleet = fleet;
    }

    public Scenario make(String type, long seed) {
        SplittableRandom r = new SplittableRandom(seed * 7919 + type.hashCode());
        long faultStart = T0 + WARMUP_WINDOWS * SimFleet.WINDOW_MS + r.nextInt(10) * 1000L + 500;
        long faultEnd = faultStart + FAULT_MS;
        long evalAt = faultStart + EVAL_DELAY_MS;
        // (overlapping-outage judges later: its second fault starts a minute in)
        List<Scenario.Fault> faults = new ArrayList<>();
        List<ConfigChange> changes = new ArrayList<>();
        List<String> truth = new ArrayList<>();
        String note;
        long runEnd = faultEnd + TAIL_MS;
        double blipRate = 0.0005;

        switch (type) {
            case "provider-brownout" -> {
                String c = pick(r, COLORS);
                String reg = pick(r, REGIONS);
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.015, 0.06), u(r, 30, 120), u(r, 5, 30));
                faults.add(new Scenario.Fault(type, t -> t.color().equals(c) && t.region().equals(reg), imp, faultStart, faultEnd, null));
                truth.add("provider/" + c + "/" + reg);
                note = describe(imp);
            }
            case "subtle-brownout" -> {
                // the kind users complain about while the graphs barely move
                String c = pick(r, COLORS);
                String reg = pick(r, REGIONS);
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.008, 0.02), u(r, 10, 25), u(r, 2, 8));
                faults.add(new Scenario.Fault(type, t -> t.color().equals(c) && t.region().equals(reg), imp, faultStart, faultEnd, null));
                truth.add("provider/" + c + "/" + reg);
                note = describe(imp);
            }
            case "noisy-brownout" -> {
                String c = pick(r, COLORS);
                String reg = pick(r, REGIONS);
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.015, 0.06), u(r, 30, 120), u(r, 5, 30));
                faults.add(new Scenario.Fault(type, t -> t.color().equals(c) && t.region().equals(reg), imp, faultStart, faultEnd, null));
                truth.add("provider/" + c + "/" + reg);
                blipRate = 0.005;
                note = describe(imp) + ", ten times the background blips";
            }
            case "jitter-storm" -> {
                String c = pick(r, COLORS);
                String reg = pick(r, REGIONS);
                SimFleet.Impairment imp = new SimFleet.Impairment(0, u(r, 0, 10), u(r, 25, 50));
                faults.add(new Scenario.Fault(type, t -> t.color().equals(c) && t.region().equals(reg), imp, faultStart, faultEnd, null));
                truth.add("provider/" + c + "/" + reg);
                note = describe(imp);
            }
            case "carrier-outage" -> {
                String c = r.nextBoolean() ? "biz-internet" : "lte";
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.02, 0.05), u(r, 40, 100), u(r, 5, 20));
                faults.add(new Scenario.Fault(type, t -> t.color().equals(c), imp, faultStart, faultEnd, null));
                truth.add("carrier/" + c);
                note = describe(imp);
            }
            case "circuit-blackout" -> {
                SimFleet.Site s = site(r);
                String c = s.colors().get(r.nextInt(s.colors().size()));
                faults.add(new Scenario.Fault(type, t -> t.siteId() == s.siteId() && t.color().equals(c),
                        new SimFleet.Impairment(1, 0, 0), faultStart, faultEnd, null));
                truth.add("site/" + s.siteId() + "/" + c);
                note = "all probes lost";
            }
            case "circuit-brownout" -> {
                SimFleet.Site s = site(r);
                String c = s.colors().get(r.nextInt(s.colors().size()));
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.08, 0.2), u(r, 40, 80), u(r, 5, 15));
                faults.add(new Scenario.Fault(type, t -> t.siteId() == s.siteId() && t.color().equals(c), imp, faultStart, faultEnd, null));
                truth.add("site/" + s.siteId() + "/" + c);
                note = describe(imp);
            }
            case "site-down" -> {
                SimFleet.Site s = site(r);
                faults.add(new Scenario.Fault(type, t -> t.siteId() == s.siteId(), new SimFleet.Impairment(1, 0, 0),
                        faultStart, faultEnd, null));
                truth.add("site/" + s.siteId());
                note = "all probes lost on every transport";
            }
            case "hub-degradation" -> {
                String h = pick(r, HUBS);
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.02, 0.05), u(r, 30, 80), u(r, 5, 15));
                faults.add(new Scenario.Fault(type, t -> t.hub().equals(h), imp, faultStart, faultEnd, null));
                truth.add("hub/" + h);
                note = describe(imp);
            }
            case "hub-circuit" -> {
                String h = pick(r, HUBS);
                String c = pick(r, COLORS);
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.02, 0.06), u(r, 30, 80), u(r, 5, 15));
                faults.add(new Scenario.Fault(type, t -> t.hub().equals(h) && t.color().equals(c), imp, faultStart, faultEnd, null));
                truth.add("hub/" + h + "/" + c);
                note = describe(imp);
            }
            case "bad-rollout" -> {
                String c = r.nextInt(4) == 0 ? pick(r, COLORS) : "biz-internet";
                boolean mtu = r.nextBoolean();
                SimFleet.Impairment imp = mtu
                        ? new SimFleet.Impairment(u(r, 0.04, 0.07), 0, 0)
                        : new SimFleet.Impairment(0, u(r, 0, 5), u(r, 30, 45));
                String dep = uuid(r);
                List<String> diff = List.of(mtu
                        ? "~ transports[color=" + c + "].mtu: 1500 -> 1380"
                        : "~ transports[color=" + c + "].qos-policy: voice-priority -> default");
                Map<String, Long> applied = rollout(r, c, dep, "branch v" + (2 + r.nextInt(8)), diff, faultStart,
                        new double[] {0.05, 0.25, 1.0}, new long[] {0, 180_000, 360_000}, changes);
                faults.add(new Scenario.Fault(type, t -> t.color().equals(c) && applied.containsKey(t.systemIp()), imp,
                        faultStart, Long.MAX_VALUE, t -> applied.get(t.systemIp())));
                truth.add("change/" + dep + "/" + c);
                runEnd = faultStart + 240_000;
                faultEnd = Long.MAX_VALUE;
                note = (mtu ? "MTU change, " : "QoS change, ") + describe(imp) + ", canary of "
                        + applied.values().stream().filter(t -> t < faultStart + 180_000).count() + " routers";
            }
            case "red-herring-rollout" -> {
                String c = r.nextBoolean() ? "biz-internet" : pick(r, COLORS);
                String reg = pick(r, REGIONS);
                String dep = uuid(r);
                rollout(r, c, dep, "branch v" + (2 + r.nextInt(8)), List.of("~ transports[color=" + c + "].description"),
                        faultStart - 240_000, new double[] {0.05, 0.25, 1.0}, new long[] {0, 60_000, 120_000}, changes);
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.015, 0.06), u(r, 30, 120), u(r, 5, 30));
                faults.add(new Scenario.Fault(type, t -> t.color().equals(c) && t.region().equals(reg), imp, faultStart, faultEnd, null));
                truth.add("provider/" + c + "/" + reg);
                note = "harmless fleet-wide rollout on " + c + " shortly before a " + reg + " brownout, " + describe(imp);
            }
            case "compound" -> {
                String c = pick(r, COLORS);
                String reg = pick(r, REGIONS);
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.015, 0.06), u(r, 30, 120), u(r, 5, 30));
                faults.add(new Scenario.Fault("provider", t -> t.color().equals(c) && t.region().equals(reg), imp,
                        faultStart, faultEnd, null));
                truth.add("provider/" + c + "/" + reg);
                SimFleet.Site s;
                do {
                    s = site(r);
                } while (s.region().equals(reg));
                SimFleet.Site site = s;
                if (r.nextBoolean()) {
                    String c2 = site.colors().get(r.nextInt(site.colors().size()));
                    faults.add(new Scenario.Fault("circuit", t -> t.siteId() == site.siteId() && t.color().equals(c2),
                            new SimFleet.Impairment(1, 0, 0), faultStart, faultEnd, null));
                    truth.add("site/" + site.siteId() + "/" + c2);
                } else {
                    faults.add(new Scenario.Fault("site", t -> t.siteId() == site.siteId(),
                            new SimFleet.Impairment(1, 0, 0), faultStart, faultEnd, null));
                    truth.add("site/" + site.siteId());
                }
                note = "provider brownout plus an unrelated outage in another region";
            }
            case "overlapping-outage" -> {
                // a circuit dies at a site whose other transport is degraded by a regional
                // brownout: all of that site's tunnels are red, for two different reasons
                SimFleet.Site site = site(r);
                String dead = site.colors().get(r.nextInt(2));
                String other = site.colors().get(dead.equals(site.colors().get(0)) ? 1 : 0);
                SimFleet.Impairment imp = new SimFleet.Impairment(u(r, 0.02, 0.06), u(r, 40, 120), u(r, 5, 30));
                faults.add(new Scenario.Fault("provider", t -> t.color().equals(other) && t.region().equals(site.region()),
                        imp, faultStart, faultEnd, null));
                faults.add(new Scenario.Fault("circuit", t -> t.siteId() == site.siteId() && t.color().equals(dead),
                        new SimFleet.Impairment(1, 0, 0), faultStart + 60_000, faultEnd, null));
                truth.add("provider/" + other + "/" + site.region());
                truth.add("site/" + site.siteId() + "/" + dead);
                evalAt = faultStart + 150_000;
                note = dead + " circuit down at a site inside a " + other + " brownout";
            }
            case "benign-rollout" -> {
                String c = pick(r, COLORS);
                rollout(r, c, uuid(r), "branch v" + (2 + r.nextInt(8)), List.of("~ snmp.location"), faultStart,
                        new double[] {0.05, 0.25, 1.0}, new long[] {0, 60_000, 120_000}, changes);
                note = "staged rollout that changes nothing that matters";
            }
            case "noise-only" -> note = "background noise only";
            default -> throw new IllegalArgumentException(type);
        }

        addBlips(r, faults, runEnd, blipRate);
        int windows = (int) ((runEnd - T0) / SimFleet.WINDOW_MS);
        changes.sort((a, b) -> Long.compare(a.appliedAt(), b.appliedAt()));
        return new Scenario(type, seed, T0, windows, List.copyOf(faults), List.copyOf(changes), List.copyOf(truth),
                faultStart, faultEnd, evalAt, note);
    }

    /** Staged rollout over the routers that have {@code color}; returns apply time per router. */
    private Map<String, Long> rollout(SplittableRandom r, String color, String dep, String label, List<String> diff,
                                      long start, double[] cumulative, long[] offsets, List<ConfigChange> out) {
        List<SimFleet.Site> targets = new ArrayList<>(fleet.sites().stream().filter(s -> s.colors().contains(color)).toList());
        Collections.shuffle(targets, new java.util.Random(r.nextLong()));
        Map<String, Long> applied = new HashMap<>();
        int done = 0;
        for (int b = 0; b < cumulative.length; b++) {
            int upTo = Math.max(done + 1, Math.min(targets.size(), (int) Math.ceil(cumulative[b] * targets.size())));
            if (b == 0) {
                upTo = Math.max(3, upTo);
            }
            for (int i = done; i < upTo && i < targets.size(); i++) {
                SimFleet.Site s = targets.get(i);
                long at = start + offsets[b] + r.nextInt(20_000);
                applied.put(s.systemIp(), at);
                out.add(new ConfigChange(dep, label, s.systemIp(), s.siteId(), at, 7 + b, color, diff));
            }
            done = Math.min(upTo, targets.size());
        }
        return applied;
    }

    private void addBlips(SplittableRandom r, List<Scenario.Fault> faults, long runEnd, double rate) {
        List<Tunnel> tunnels = fleet.tunnels();
        long tunnelWindows = (long) tunnels.size() * ((runEnd - T0) / SimFleet.WINDOW_MS);
        int blips = (int) Math.round(tunnelWindows * rate);
        for (int i = 0; i < blips; i++) {
            Tunnel t = tunnels.get(r.nextInt(tunnels.size()));
            long start = T0 + (long) (r.nextDouble() * (runEnd - T0));
            long dur = 10_000 + r.nextInt(10_001);
            String key = t.key();
            Predicate<Tunnel> m = x -> x.key().equals(key);
            faults.add(new Scenario.Fault("blip", m, new SimFleet.Impairment(0.33, 0, 0), start, start + dur, null));
        }
    }

    private SimFleet.Site site(SplittableRandom r) {
        return fleet.sites().get(r.nextInt(fleet.sites().size()));
    }

    private static String pick(SplittableRandom r, String[] xs) {
        return xs[r.nextInt(xs.length)];
    }

    private static double u(SplittableRandom r, double lo, double hi) {
        return lo + r.nextDouble() * (hi - lo);
    }

    private static String uuid(SplittableRandom r) {
        return new UUID(r.nextLong(), r.nextLong()).toString();
    }

    private static String describe(SimFleet.Impairment imp) {
        return String.format("loss +%.1f%%, latency +%.0f ms, jitter +%.0f ms", imp.loss() * 100, imp.latencyMs(), imp.jitterMs());
    }
}
