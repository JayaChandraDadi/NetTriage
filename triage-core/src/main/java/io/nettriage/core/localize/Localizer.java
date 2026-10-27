package io.nettriage.core.localize;

import io.nettriage.core.model.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the smallest set of failed elements that best explains which tunnels are
 * degraded, which are not, and what the degradation looks like.
 *
 * <h2>Model</h2>
 * Every observed tunnel is degraded or healthy. A tunnel degrades on its own with a
 * small background probability {@code eps} (noise, a flaky last mile). Each failed
 * element {@code e} degrades each tunnel it covers with probability {@code p_e},
 * independently (noisy-OR):
 *
 * <pre>  P(tunnel degraded) = 1 - (1 - eps) * prod over failed e covering it of (1 - p_e)</pre>
 *
 * Healthy tunnels matter as much as degraded ones: blaming the whole carrier also
 * predicts that its healthy tunnels should be degraded, and those misses cost likelihood.
 *
 * <p>{@code p_e} is not assumed to be 1. A provider brownout at 3% loss trips the
 * detector on some tunnels and not others; a threshold rule that wants 80% of the
 * provider's tunnels red would miss it, while here a 55% hit rate over 200 tunnels is
 * still overwhelming evidence against "200 unrelated failures".
 *
 * <p>For an element that spans transports (a site, a hub, a rollout touching
 * everything), {@code p_e} is estimated per transport with partial pooling toward the
 * element's overall rate: {@code p_c = (hits_c + K * p) / (observed_c + K)}. A hub fault
 * that LTE's noisy baseline half hides then stays one cause instead of splitting into
 * "the hub's MPLS circuit and its internet circuit", while a fault on just one hub
 * circuit still can't be blamed on the hub: its healthy transports keep a pooled share
 * the data contradicts.
 *
 * <h2>Symptoms</h2>
 * A degraded tunnel is either blacked out (every probe lost) or performing badly (loss,
 * latency, jitter). Each cause has its own blackout share, estimated from the tunnels it
 * covers, and a degraded tunnel's symptom is scored against the causes covering it,
 * weighted by their {@code p}. This is what tells "the LTE circuit at site 102 is down"
 * from "site 102 is down" when the site's internet tunnels happen to be degraded too, by
 * a regional brownout: a dead router blacks out every transport, while those internet
 * tunnels show the same slowdown as their neighbors.
 *
 * <h2>Cost of a cause</h2>
 * A hypothesis is scored by its log-likelihood minus a cost per cause: a description
 * length, {@code lambda + ln(N)} where {@code N} is how many elements of that kind exist.
 * Naming one of 200 sites takes more evidence than naming one of two hubs, which is the
 * multiple-comparisons correction a large fleet needs: two random blips land on the same
 * site somewhere every few minutes, on the same hub almost never.
 *
 * <p>Each open incident's cause pays only {@code lambdaActive}, because faults persist.
 * That discount can instead go to one element nested inside the open cause or containing
 * it, so an explanation can sharpen (the site, then just its LTE circuit) or widen (one
 * circuit, then the whole site) without the new wording paying for a brand-new fault.
 * It is one discount per open incident, and only for a re-wording that still explains
 * at least 80% of what is degraded inside the open cause: splitting a hub fault into the
 * hub's circuits, or pinning a leftover blip inside a fading provider fault on one
 * circuit, pays full price.
 *
 * <h2>Search</h2>
 * Greedy forward selection (add the element with the largest score gain net of its cost),
 * then backward pruning (drop any element whose removal raises the score, which happens
 * when a broad element chosen early is later made redundant). For each chosen cause, the
 * margin to its best competing explanation of the same tunnels is reported: that number
 * is the confidence.
 */
public final class Localizer {

    /**
     * @param eps          floor for the background probability that a tunnel is degraded with
     *                     no shared cause; the engine raises it when the network is noisier
     * @param lambda       base log-likelihood a new cause must buy, in nats, before the
     *                     ln(number of elements of its kind) naming cost
     * @param lambdaActive what a cause that is already an open incident must buy to stay
     * @param maxCauses    upper bound on simultaneous causes
     */
    public record Settings(double eps, double lambda, double lambdaActive, int maxCauses) {
        public static Settings defaults() {
            return new Settings(0.002, 4.0, 2.0, 8);
        }

        public Settings withEps(double e) {
            return new Settings(e, lambda, lambdaActive, maxCauses);
        }
    }

    /**
     * A candidate element and the tunnel indices it covers.
     *
     * @param groups optional group per covered tunnel (its transport), same length as
     *               {@code cover}; when present, the share of tunnels the element degrades
     *               is estimated per group with partial pooling (see class notes)
     */
    public record Candidate(Element element, int[] cover, int[] groups) {
        public Candidate(Element element, int[] cover) {
            this(element, cover, null);
        }
    }

    /**
     * How a chosen element fits the data.
     *
     * @param pEach         share of each covered tunnel it degrades
     * @param blackoutShare share of its degraded tunnels expected to be blacked out
     * @param covered       observed tunnels it covers
     * @param hits          of those, how many are degraded
     * @param p             estimated share of its tunnels it degrades
     * @param contribution  score lost if this element were dropped from the explanation
     */
    public record Fit(Element element, int[] cover, double[] pEach, double blackoutShare, int covered, int hits,
                      double p, double contribution) {
    }

    /**
     * A competing explanation for the same tunnels.
     *
     * @param overlap how many of the chosen cause's degraded tunnels this one also covers
     * @param margin  how much worse the overall explanation gets if this element replaces
     *                the chosen one; small means hard to tell apart
     */
    public record Alternative(Element element, int covered, int hits, int overlap, double margin) {
    }

    public enum Confidence { HIGH, MEDIUM, LOW }

    /** One root cause in the explanation. */
    public record Cause(Fit fit, List<Alternative> alternatives, double margin) {
        public Confidence confidence() {
            return margin >= 10 ? Confidence.HIGH : margin >= 4 ? Confidence.MEDIUM : Confidence.LOW;
        }
    }

    public record Result(List<Cause> causes, int observed, int degraded, int unexplained, double score,
                         int candidates) {
        public static Result empty(int observed) {
            return new Result(List.of(), observed, 0, 0, 0, 0);
        }
    }

    public static final byte EXCLUDED = 0;
    public static final byte HEALTHY = 1;
    public static final byte DEGRADED = 2;

    /**
     * A new cause must degrade at least this share of what depends on it. Without a floor,
     * a hub "degrading 2% of its tunnels" explains scattered noise better than noise does.
     */
    private static final double MIN_P = 0.1;
    /** A cause that is already an open incident may fade this far and still hold. */
    private static final double MIN_P_ACTIVE = 0.02;
    private static final double MAX_P = 0.995;
    private static final double POOL = 20;
    /** Share of background noise that shows up as a blackout rather than a slowdown. */
    private static final double NOISE_BLACKOUT = 0.1;
    private static final Comparator<Candidate> TIE_BREAK = Comparator
            .<Candidate>comparingInt(c -> c.element().kind().ordinal())
            .thenComparingInt(c -> c.cover().length)
            .thenComparing(c -> c.element().id());

    private final Settings settings;
    private final Set<Element> active;
    private final Map<Element.Kind, Integer> population;
    /** For each discount-eligible element, the open causes whose discount it may use. */
    private Map<Element, List<Element>> eligible = Map.of();
    private boolean[] blackout;

    public Localizer(Settings settings) {
        this(settings, Set.of(), Map.of());
    }

    /**
     * @param active     causes of currently open incidents, which pay {@code lambdaActive}
     * @param population how many elements of each kind exist, for the naming cost
     */
    public Localizer(Settings settings, Set<Element> active, Map<Element.Kind, Integer> population) {
        this.settings = settings;
        this.active = active;
        this.population = population;
        Map<Element, List<Element>> m = new HashMap<>();
        active.forEach(a -> m.put(a, List.of(a)));
        this.eligible = m;
    }

    /** What a new, unrelated cause must buy to be believed, in nats. */
    public double cost(Element e) {
        return active.contains(e) ? settings.lambdaActive() : fullCost(e);
    }

    private double fullCost(Element e) {
        return settings.lambda() + Math.log(Math.max(1, population.getOrDefault(e.kind(), 1)));
    }

    /**
     * Total cost of a set of causes: full description lengths, minus at most one discount
     * per open incident, given to whichever eligible member it saves the most on.
     */
    private double cost(java.util.Collection<Element> causes) {
        double total = 0;
        for (Element e : causes) {
            total += fullCost(e);
        }
        Set<Element> usedParents = new HashSet<>();
        Set<Element> discountedMembers = new HashSet<>();
        List<Element> ordered = new ArrayList<>(causes);
        ordered.sort(Comparator.comparingDouble((Element e) -> -fullCost(e)).thenComparing(Element::id));
        // an open cause itself takes its own discount first
        for (Element e : ordered) {
            if (active.contains(e) && usedParents.add(e)) {
                total -= fullCost(e) - settings.lambdaActive();
                discountedMembers.add(e);
            }
        }
        for (Element e : ordered) {
            if (discountedMembers.contains(e)) {
                continue;
            }
            for (Element parent : eligible.getOrDefault(e, List.of())) {
                if (usedParents.add(parent)) {
                    total -= Math.max(0, fullCost(e) - settings.lambdaActive());
                    discountedMembers.add(e);
                    break;
                }
            }
        }
        return total;
    }

    private double costOfFits(List<Fit> fits) {
        return cost(fits.stream().map(Fit::element).toList());
    }

    /** Extra cost of adding {@code e} to {@code chosen}. */
    private double marginal(Element e, List<Fit> chosen) {
        List<Element> with = new ArrayList<>(chosen.stream().map(Fit::element).toList());
        double before = cost(with);
        with.add(e);
        return cost(with) - before;
    }

    private double minP(Element e) {
        return active.contains(e) ? MIN_P_ACTIVE : MIN_P;
    }

    /** Without symptom information: every degraded tunnel counts as a slowdown. */
    public Result localize(byte[] state, List<Candidate> candidates) {
        return localize(state, null, candidates);
    }

    /**
     * @param state      per tunnel index: {@link #EXCLUDED}, {@link #HEALTHY} or {@link #DEGRADED}
     * @param blackout   per tunnel index, whether a degraded tunnel is blacked out; null to ignore symptoms
     * @param candidates elements worth considering, normally those covering a degraded tunnel
     */
    public Result localize(byte[] state, boolean[] blackout, List<Candidate> candidates) {
        this.blackout = blackout;
        int observed = 0;
        int degraded = 0;
        for (byte b : state) {
            if (b != EXCLUDED) {
                observed++;
            }
            if (b == DEGRADED) {
                degraded++;
            }
        }
        if (degraded == 0) {
            return Result.empty(observed);
        }
        eligible = withRelatives(candidates, state);

        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(TIE_BREAK);

        Model model = new Model(state.length);
        List<Fit> chosen = new ArrayList<>();
        Set<Element> chosenSet = new HashSet<>();
        while (chosen.size() < settings.maxCauses()) {
            Candidate best = null;
            double bestGain = 0;
            Fit bestFit = null;
            for (Candidate c : sorted) {
                if (chosenSet.contains(c.element())) {
                    continue;
                }
                int[] counts = counts(c.cover(), state);
                if (counts[1] == 0) {
                    continue;
                }
                Fit f = fit(c, state, minP(c.element()));
                double gain = model.gain(f, state) - marginal(c.element(), chosen);
                if (gain > bestGain + 1e-9) {
                    best = c;
                    bestGain = gain;
                    bestFit = f;
                }
            }
            if (best == null) {
                break;
            }
            chosen.add(bestFit);
            chosenSet.add(best.element());
            model.add(bestFit);
        }

        prune(chosen, state);

        double total = score(chosen, state);
        List<Cause> causes = new ArrayList<>();
        for (Fit f : chosen) {
            List<Fit> without = new ArrayList<>(chosen);
            without.remove(f);
            Model modelWithout = model(without, state.length);
            double scoreWithout = modelWithout.logLik(state) - costOfFits(without);
            double contribution = total - scoreWithout;
            double ownCost = marginal(f.element(), without);
            Fit withContribution = new Fit(f.element(), f.cover(), f.pEach(), f.blackoutShare(), f.covered(), f.hits(),
                    f.p(), contribution);
            List<Alternative> alts = alternatives(f, sorted, chosenSet, state, modelWithout, scoreWithout, total, ownCost);
            double margin = contribution;
            for (Alternative a : alts) {
                margin = Math.min(margin, a.margin());
            }
            causes.add(new Cause(withContribution, alts, margin));
        }
        causes.sort(Comparator.comparingInt((Cause c) -> -c.fit().hits()).thenComparing(c -> c.fit().element().id()));

        Set<Integer> explained = new HashSet<>();
        for (Fit f : chosen) {
            for (int i : f.cover()) {
                if (state[i] == DEGRADED) {
                    explained.add(i);
                }
            }
        }
        return new Result(List.copyOf(causes), observed, degraded, degraded - explained.size(), total, candidates.size());
    }

    /**
     * Open causes, plus every candidate nested inside one of them or containing one, mapped
     * to the open causes whose discount they may use.
     */
    private Map<Element, List<Element>> withRelatives(List<Candidate> candidates, byte[] state) {
        Map<Element, List<Element>> out = new HashMap<>();
        active.forEach(a -> out.put(a, new ArrayList<>(List.of(a))));
        if (active.isEmpty()) {
            return out;
        }
        Map<Element, Set<Integer>> activeCovers = new HashMap<>();
        for (Candidate c : candidates) {
            if (active.contains(c.element())) {
                Set<Integer> s = new HashSet<>();
                for (int i : c.cover()) {
                    s.add(i);
                }
                activeCovers.put(c.element(), s);
            }
        }
        Map<Element, Integer> activeHits = new HashMap<>();
        activeCovers.forEach((e, cover) -> activeHits.put(e,
                (int) cover.stream().filter(i -> state[i] == DEGRADED).count()));
        for (Candidate c : candidates) {
            if (active.contains(c.element())) {
                continue;
            }
            int hits = counts(c.cover(), state)[1];
            if (hits < 2) {
                continue;
            }
            for (Map.Entry<Element, Set<Integer>> a : activeCovers.entrySet()) {
                int overlap = 0;
                int hitsInside = 0;
                for (int i : c.cover()) {
                    if (a.getValue().contains(i)) {
                        overlap++;
                        if (state[i] == DEGRADED) {
                            hitsInside++;
                        }
                    }
                }
                boolean nested = overlap > 0 && (overlap == c.cover().length || overlap == a.getValue().size());
                if (nested && hitsInside >= 0.8 * activeHits.get(a.getKey())) {
                    out.computeIfAbsent(c.element(), k -> new ArrayList<>()).add(a.getKey());
                }
            }
        }
        return out;
    }

    /**
     * Score difference between the chosen explanation and the same explanation with one
     * cause swapped for a candidate; used for "why this and not that".
     */
    public double swapMargin(byte[] state, List<Fit> chosen, Fit replaced, Candidate replacement) {
        List<Fit> without = new ArrayList<>(chosen);
        without.remove(replaced);
        Model m = model(without, state.length);
        double scoreWithout = m.logLik(state) - costOfFits(without);
        int[] counts = counts(replacement.cover(), state);
        if (counts[1] == 0) {
            return score(chosen, state) - scoreWithout;
        }
        // the replacement inherits the replaced cause's cost: the margin compares fit, not wording
        double swapped = scoreWithout + m.gain(fit(replacement, state, minP(replaced.element())), state)
                - marginal(replaced.element(), without);
        return score(chosen, state) - swapped;
    }

    private List<Alternative> alternatives(Fit f, List<Candidate> candidates, Set<Element> chosenSet, byte[] state,
                                           Model modelWithout, double scoreWithout, double total, double ownCost) {
        Set<Integer> rootHits = new HashSet<>();
        for (int i : f.cover()) {
            if (state[i] == DEGRADED) {
                rootHits.add(i);
            }
        }
        List<Alternative> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (chosenSet.contains(c.element())) {
                continue;
            }
            int overlap = 0;
            for (int i : c.cover()) {
                if (rootHits.contains(i)) {
                    overlap++;
                }
            }
            if (overlap == 0 || overlap * 4 < rootHits.size() && overlap < 2) {
                continue;
            }
            Fit alt = fit(c, state, minP(f.element()));
            // the replacement inherits the replaced cause's cost: a fair swap, not a new incident
            double swapped = scoreWithout + modelWithout.gain(alt, state) - ownCost;
            out.add(new Alternative(c.element(), alt.covered(), alt.hits(), overlap, total - swapped));
        }
        out.sort(Comparator.comparingDouble(Alternative::margin).thenComparing(a -> a.element().id()));
        return out.size() > 4 ? List.copyOf(out.subList(0, 4)) : List.copyOf(out);
    }

    private void prune(List<Fit> chosen, byte[] state) {
        boolean changed = true;
        while (changed && chosen.size() > 1) {
            changed = false;
            double current = score(chosen, state);
            for (Fit f : List.copyOf(chosen)) {
                List<Fit> without = new ArrayList<>(chosen);
                without.remove(f);
                if (score(without, state) > current + 1e-9) {
                    chosen.remove(f);
                    changed = true;
                    break;
                }
            }
        }
    }

    double score(List<Fit> fits, byte[] state) {
        return model(fits, state.length).logLik(state) - costOfFits(fits);
    }

    private Model model(List<Fit> fits, int n) {
        Model m = new Model(n);
        fits.forEach(m::add);
        return m;
    }

    /** Estimates how an element would fit: per-tunnel shares and its blackout share. */
    private Fit fit(Candidate c, byte[] state, double minP) {
        int[] counts = counts(c.cover(), state);
        int blackouts = 0;
        if (blackout != null) {
            for (int i : c.cover()) {
                if (state[i] == DEGRADED && blackout[i]) {
                    blackouts++;
                }
            }
        }
        double qb = (blackouts + 0.5) / (counts[1] + 1.0);
        return new Fit(c.element(), c.cover(), shares(c, state, minP), qb, counts[0], counts[1],
                counts[0] == 0 ? minP : clampP((double) counts[1] / counts[0], minP), 0);
    }

    /**
     * Per-tunnel probability of being degraded, plus, for the symptom term, the summed
     * rates of everything that could have degraded it and how much of that is blackout.
     */
    private final class Model {
        final double[] prob;
        final double[] rate;
        final double[] blackoutRate;

        Model(int n) {
            prob = new double[n];
            rate = new double[n];
            blackoutRate = new double[n];
            Arrays.fill(prob, settings.eps());
            Arrays.fill(rate, settings.eps());
            Arrays.fill(blackoutRate, settings.eps() * NOISE_BLACKOUT);
        }

        void add(Fit f) {
            int[] cover = f.cover();
            for (int k = 0; k < cover.length; k++) {
                int t = cover[k];
                double p = f.pEach()[k];
                prob[t] = 1 - (1 - prob[t]) * (1 - p);
                rate[t] += p;
                blackoutRate[t] += p * f.blackoutShare();
            }
        }

        double term(int t, byte s, double pr, double r, double br) {
            if (s == HEALTHY) {
                return Math.log1p(-pr);
            }
            double ll = Math.log(pr);
            if (blackout != null) {
                double share = br / r;
                ll += Math.log(blackout[t] ? share : 1 - share);
            }
            return ll;
        }

        /** Log-likelihood gained by adding {@code f}, without changing the model. */
        double gain(Fit f, byte[] state) {
            double g = 0;
            int[] cover = f.cover();
            for (int k = 0; k < cover.length; k++) {
                int t = cover[k];
                byte s = state[t];
                if (s == EXCLUDED) {
                    continue;
                }
                double p = f.pEach()[k];
                double after = 1 - (1 - prob[t]) * (1 - p);
                g += term(t, s, after, rate[t] + p, blackoutRate[t] + p * f.blackoutShare())
                        - term(t, s, prob[t], rate[t], blackoutRate[t]);
            }
            return g;
        }

        double logLik(byte[] state) {
            double ll = 0;
            for (int t = 0; t < state.length; t++) {
                if (state[t] != EXCLUDED) {
                    ll += term(t, state[t], prob[t], rate[t], blackoutRate[t]);
                }
            }
            return ll;
        }
    }

    /** [observed covered, degraded covered]. */
    private static int[] counts(int[] cover, byte[] state) {
        int obs = 0;
        int bad = 0;
        for (int i : cover) {
            if (state[i] != EXCLUDED) {
                obs++;
                if (state[i] == DEGRADED) {
                    bad++;
                }
            }
        }
        return new int[] {obs, bad};
    }

    /** Share of tunnels the element degrades, per covered tunnel, pooled per group. */
    static double[] shares(Candidate c, byte[] state, double minP) {
        int[] counts = counts(c.cover(), state);
        double overall = counts[0] == 0 ? minP : (double) counts[1] / counts[0];
        double[] out = new double[c.cover().length];
        if (c.groups() == null) {
            Arrays.fill(out, clampP(overall, minP));
            return out;
        }
        Map<Integer, int[]> byGroup = new HashMap<>();
        for (int k = 0; k < c.cover().length; k++) {
            byte s = state[c.cover()[k]];
            int[] g = byGroup.computeIfAbsent(c.groups()[k], x -> new int[2]);
            if (s != EXCLUDED) {
                g[0]++;
                if (s == DEGRADED) {
                    g[1]++;
                }
            }
        }
        for (int k = 0; k < out.length; k++) {
            int[] g = byGroup.get(c.groups()[k]);
            out[k] = clampP((g[1] + POOL * overall) / (g[0] + POOL), minP);
        }
        return out;
    }

    private static double clampP(double p, double minP) {
        return Math.max(minP, Math.min(MAX_P, p));
    }
}
