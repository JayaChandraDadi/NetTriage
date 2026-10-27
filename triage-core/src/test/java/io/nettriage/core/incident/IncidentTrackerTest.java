package io.nettriage.core.incident;

import io.nettriage.core.Topology;
import io.nettriage.core.WindowView;
import io.nettriage.core.detect.TunnelState;
import io.nettriage.core.localize.Localizer;
import io.nettriage.core.model.Element;
import io.nettriage.core.model.Tunnel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentTrackerTest {

    private final Topology topo = new Topology();
    private final List<TunnelState> states = new ArrayList<>();
    private final IncidentTracker tracker = new IncidentTracker(3, 100, 100);
    private long now = 1_000_000;

    @BeforeEach
    void fleet() {
        for (int s = 0; s < 200; s++) {
            for (String color : List.of("mpls", "inet")) {
                for (String hub : List.of("H1", "H2")) {
                    String ip = "10.1.0." + s;
                    topo.register(new Tunnel(ip + ":" + color + "->" + hub, s, ip, s < 100 ? "west" : "east", color, hub));
                    states.add(new TunnelState());
                }
            }
        }
    }

    /** One window in which exactly the matching tunnels are degraded. */
    private void window(Predicate<Tunnel> degraded) {
        now += 10_000;
        byte[] state = new byte[topo.size()];
        Arrays.fill(state, Localizer.HEALTHY);
        Set<Element> elements = new LinkedHashSet<>();
        for (int i = 0; i < topo.size(); i++) {
            if (degraded.test(topo.tunnel(i))) {
                state[i] = Localizer.DEGRADED;
                elements.addAll(Topology.elementsOf(topo.tunnel(i)));
            }
        }
        List<Localizer.Candidate> c = new ArrayList<>();
        elements.forEach(e -> c.add(new Localizer.Candidate(e, topo.cover(e))));
        Set<Element> active = new java.util.HashSet<>();
        tracker.openIncidents().forEach(i -> active.add(Element.parse(i.rootCause())));
        Localizer.Result r = new Localizer(Localizer.Settings.defaults(), active, topo.population()).localize(state, c);
        tracker.update(r, new WindowView(now, topo, state, states, Map.of(), Map.of()));
    }

    @Test
    void outageRightAfterAHubFaultIsANewIncidentNotARelabel() {
        window(t -> t.hub().equals("H2"));
        window(t -> t.hub().equals("H2"));
        window(t -> false);
        window(t -> false);
        window(t -> t.siteId() == 3);
        window(t -> t.siteId() == 3);
        List<IncidentView> all = tracker.all();
        assertThat(all).extracting(IncidentView::rootCause).containsExactlyInAnyOrder("hub/H2", "site/3");
        assertThat(all).allSatisfy(i -> assertThat(i.history()).hasSize(1));
    }

    @Test
    void sharpenedExplanationKeepsTheIncident() {
        window(t -> t.siteId() == 3);
        window(t -> t.siteId() == 3);
        window(t -> t.siteId() == 3 && t.color().equals("inet"));
        window(t -> t.siteId() == 3 && t.color().equals("inet"));
        List<IncidentView> all = tracker.all();
        assertThat(all).singleElement().satisfies(i -> {
            assertThat(i.rootCause()).isEqualTo("site/3/inet");
            assertThat(i.history()).extracting(IncidentView.RootCauseChange::rootCause).containsExactly("site/3", "site/3/inet");
        });
    }
}
