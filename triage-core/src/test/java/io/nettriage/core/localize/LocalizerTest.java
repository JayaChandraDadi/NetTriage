package io.nettriage.core.localize;

import io.nettriage.core.Topology;
import io.nettriage.core.model.Element;
import io.nettriage.core.model.Tunnel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 200 sites in two regions, each with mpls and inet tunnels to two hubs: 800 tunnels.
 */
class LocalizerTest {

    private final Topology topo = new Topology();
    private byte[] state;

    @BeforeEach
    void fleet() {
        for (int s = 0; s < 200; s++) {
            String region = s < 100 ? "west" : "east";
            for (String color : List.of("mpls", "inet")) {
                for (String hub : List.of("H1", "H2")) {
                    String ip = "10.1.0." + s;
                    topo.register(new Tunnel(ip + ":" + color + "->" + hub, s, ip, region, color, hub));
                }
            }
        }
        state = new byte[topo.size()];
        Arrays.fill(state, Localizer.HEALTHY);
    }

    private void degrade(Predicate<Tunnel> p) {
        for (int i = 0; i < topo.size(); i++) {
            if (p.test(topo.tunnel(i))) {
                state[i] = Localizer.DEGRADED;
            }
        }
    }

    private boolean[] blackout;
    private java.util.Set<Element> active = Set.of();

    private Localizer.Result run(Localizer.Candidate... extra) {
        Set<Element> elements = new LinkedHashSet<>();
        for (int i = 0; i < topo.size(); i++) {
            if (state[i] == Localizer.DEGRADED) {
                elements.addAll(Topology.elementsOf(topo.tunnel(i)));
            }
        }
        List<Localizer.Candidate> c = new ArrayList<>();
        elements.forEach(e -> c.add(new Localizer.Candidate(e, topo.cover(e))));
        c.addAll(List.of(extra));
        java.util.Map<Element.Kind, Integer> pop = topo.population();
        pop.put(Element.Kind.CHANGE, 1);
        return new Localizer(Localizer.Settings.defaults(), active, pop).localize(state, blackout, c);
    }

    private static List<String> roots(Localizer.Result r) {
        return r.causes().stream().map(c -> c.fit().element().id()).sorted().toList();
    }

    @Test
    void regionalProviderOutageIsOneCause() {
        degrade(t -> t.color().equals("inet") && t.region().equals("west"));
        Localizer.Result r = run();
        assertThat(roots(r)).containsExactly("provider/inet/west");
        assertThat(r.causes().get(0).confidence()).isEqualTo(Localizer.Confidence.HIGH);
        assertThat(r.unexplained()).isZero();
    }

    @Test
    void partialBrownoutStillPointsAtTheProvider() {
        Random rnd = new Random(1);
        degrade(t -> t.color().equals("inet") && t.region().equals("west") && rnd.nextDouble() < 0.5);
        assertThat(roots(run())).containsExactly("provider/inet/west");
    }

    @Test
    void oneCircuitDownIsTheCircuitNotTheSiteOrProvider() {
        degrade(t -> t.siteId() == 3 && t.color().equals("inet"));
        Localizer.Result r = run();
        assertThat(roots(r)).containsExactly("site/3/inet");
        assertThat(r.causes().get(0).alternatives()).extracting(a -> a.element().id()).contains("site/3");
    }

    @Test
    void circuitBlackoutDuringARegionalBrownoutIsStillTheCircuit() {
        // site 3's inet tunnels are degraded by the brownout, its mpls tunnels are dead:
        // all four of its tunnels are red, but the symptoms differ
        degrade(t -> t.color().equals("inet") && t.region().equals("west"));
        degrade(t -> t.siteId() == 3);
        blackout = new boolean[topo.size()];
        for (int i = 0; i < topo.size(); i++) {
            blackout[i] = topo.tunnel(i).siteId() == 3 && topo.tunnel(i).color().equals("mpls");
        }
        assertThat(roots(run())).containsExactly("provider/inet/west", "site/3/mpls");
    }

    @Test
    void openSiteIncidentCanSharpenToOneCircuit() {
        active = Set.of(Element.site(3));
        degrade(t -> t.siteId() == 3 && t.color().equals("inet"));
        assertThat(roots(run())).containsExactly("site/3/inet");
    }

    @Test
    void wholeSiteDownIsTheSite() {
        degrade(t -> t.siteId() == 3);
        assertThat(roots(run())).containsExactly("site/3");
    }

    @Test
    void hubProblemIsTheHub() {
        degrade(t -> t.hub().equals("H2"));
        assertThat(roots(run())).containsExactly("hub/H2");
    }

    @Test
    void twoBlipsThatHappenToShareASiteAreAtMostAWeakSuspicion() {
        // one window of this is not enough to page anyone; the incident tracker also
        // wants it to persist before it opens an incident at low confidence
        degrade(t -> t.key().equals("10.1.0.7:mpls->H1") || t.key().equals("10.1.0.7:inet->H2"));
        assertThat(run().causes()).allSatisfy(c -> assertThat(c.confidence()).isEqualTo(Localizer.Confidence.LOW));
    }

    @Test
    void isolatedTunnelBlipIsNoiseNotACause() {
        degrade(t -> t.key().equals("10.1.0.7:mpls->H1"));
        Localizer.Result r = run();
        assertThat(r.causes()).isEmpty();
        assertThat(r.unexplained()).isEqualTo(1);
    }

    @Test
    void twoIndependentFaultsAreBothFound() {
        degrade(t -> t.color().equals("inet") && t.region().equals("west"));
        degrade(t -> t.siteId() == 130 && t.color().equals("mpls"));
        assertThat(roots(run())).containsExactly("provider/inet/west", "site/130/mpls");
    }

    @Test
    void scatteredRoutersThatShareARolloutAreExplainedByTheRollout() {
        Set<String> changed = Set.of("10.1.0.2", "10.1.0.49", "10.1.0.77", "10.1.0.123", "10.1.0.151", "10.1.0.188");
        degrade(t -> changed.contains(t.systemIp()) && t.color().equals("inet"));
        int[] cover = topo.coverOfRouters(changed, "inet");
        Localizer.Result r = run(new Localizer.Candidate(Element.change("dep1", "inet"), cover));
        assertThat(roots(r)).containsExactly("change/dep1/inet");
    }

    @Test
    void fleetWideRolloutDoesNotTakeTheBlameForARegionalOutage() {
        degrade(t -> t.color().equals("inet") && t.region().equals("west"));
        Set<String> all = new LinkedHashSet<>();
        topo.tunnels().forEach(t -> all.add(t.systemIp()));
        int[] cover = topo.coverOfRouters(all, "inet");
        Localizer.Result r = run(new Localizer.Candidate(Element.change("dep1", "inet"), cover));
        assertThat(roots(r)).containsExactly("provider/inet/west");
        assertThat(r.causes().get(0).alternatives()).extracting(a -> a.element().id()).contains("change/dep1/inet");
    }
}
