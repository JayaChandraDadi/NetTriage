package io.nettriage.bench;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.Views;
import io.nettriage.core.explain.Action;
import io.nettriage.core.explain.Evidence;
import io.nettriage.core.incident.IncidentView;
import io.nettriage.core.model.ConfigChange;
import io.nettriage.core.model.TunnelWindow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Whole-engine behavior on simulated fleets: diagnosis, evidence, actions, lifecycle. */
class EngineScenarioTest {

    private static final int SITES = 200;

    /** Replays a scenario up to {@code until} and returns the engine. */
    private static TriageEngine replay(Scenario sc, long until) {
        SimFleet fleet = new SimFleet(SITES);
        SplittableRandom rnd = new SplittableRandom(sc.seed());
        TriageEngine engine = new TriageEngine();
        int ci = 0;
        for (int w = 1; w <= sc.windows(); w++) {
            long end = sc.startMs() + w * SimFleet.WINDOW_MS;
            if (end > until) {
                break;
            }
            while (ci < sc.changes().size() && sc.changes().get(ci).appliedAt() <= end) {
                engine.addChange(sc.changes().get(ci++));
            }
            List<TunnelWindow> obs = fleet.window(end, sc.impairments(), rnd);
            engine.processWindow(end, obs);
        }
        return engine;
    }

    private static Scenario scenario(String type, long seed) {
        return new ScenarioFactory(new SimFleet(SITES)).make(type, seed);
    }

    private static String text(IncidentView i) {
        return String.join("\n", i.evidence().stream().map(Evidence::text).toList());
    }

    @Test
    void providerBrownoutBecomesOneIncidentWithContrastingEvidence() {
        Scenario sc = scenario("provider-brownout", 3);
        TriageEngine engine = replay(sc, sc.evalAt());
        List<IncidentView> open = engine.incidents(false);
        assertThat(open).extracting(IncidentView::rootCause).containsExactlyElementsOf(sc.truth());
        IncidentView inc = open.get(0);
        assertThat(inc.confidence()).isEqualTo("HIGH");
        assertThat(text(inc)).contains("in other regions is fine").contains("Other transports in");
        assertThat(inc.evidence()).extracting(Evidence::id).startsWith("E1", "E2");
        assertThat(inc.actions()).extracting(Action::kind).containsOnly(Action.Kind.ADVISORY);
    }

    @Test
    void badRolloutIsBlamedAtTheCanaryAndRollbackIsProposed() {
        Scenario sc = scenario("bad-rollout", 4);
        TriageEngine engine = replay(sc, sc.evalAt());
        IncidentView inc = engine.incidents(false).get(0);
        assertThat(inc.rootCause()).isEqualTo(sc.truth().get(0));
        assertThat(text(inc)).contains("Routers that have not applied it are fine").contains("What changed:");
        assertThat(inc.actions()).anySatisfy(a -> {
            assertThat(a.kind()).isEqualTo(Action.Kind.ROLLBACK_DEPLOYMENT);
            assertThat(a.deploymentId()).isEqualTo(sc.changes().get(0).deploymentId());
        });
        long canary = sc.changes().stream().filter(c -> c.appliedAt() < sc.faultStart() + 180_000).count();
        assertThat(inc.sitesAffected()).isLessThanOrEqualTo((int) canary);
    }

    @Test
    void rolloutThatCoincidesWithABrownoutIsExplicitlyCleared() {
        Scenario sc = scenario("red-herring-rollout", 2);
        TriageEngine engine = replay(sc, sc.evalAt());
        IncidentView inc = engine.incidents(false).get(0);
        assertThat(inc.rootCause()).isEqualTo(sc.truth().get(0));
        assertThat(text(inc)).contains("The rollout does not explain the pattern");
    }

    @Test
    void incidentResolvesAfterTheFaultEndsAndNoiseRaisesNothing() {
        Scenario sc = scenario("circuit-blackout", 5);
        TriageEngine engine = replay(sc, Long.MAX_VALUE);
        List<IncidentView> all = engine.incidents(true);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).state()).isEqualTo("RESOLVED");
        assertThat(all.get(0).resolvedAt()).isBetween(sc.faultEnd(), sc.faultEnd() + 90_000);

        Scenario quiet = scenario("noise-only", 5);
        assertThat(replay(quiet, Long.MAX_VALUE).incidents(true)).isEmpty();
    }

    @Test
    void circuitDyingInsideABrownoutIsNamedAsTheCircuit() {
        Scenario sc = scenario("overlapping-outage", 2);
        TriageEngine engine = replay(sc, sc.evalAt());
        assertThat(engine.incidents(false)).extracting(IncidentView::rootCause)
                .containsExactlyInAnyOrderElementsOf(sc.truth());
    }

    @Test
    void compareExplainsWhyTheRootBeatsAnAlternative() {
        Scenario sc = scenario("provider-brownout", 3);
        TriageEngine engine = replay(sc, sc.evalAt());
        IncidentView inc = engine.incidents(false).get(0);
        String color = inc.rootCause().split("/")[1];
        Views.Comparison c = engine.compare(inc.id(), "carrier/" + color);
        assertThat(c.margin()).isGreaterThan(4);
        assertThat(c.verdict()).contains("fits much better");
    }

    @Test
    void telemetryGapIsSkippedNotDiagnosed() {
        Scenario sc = scenario("noise-only", 1);
        SimFleet fleet = new SimFleet(SITES);
        SplittableRandom rnd = new SplittableRandom(1);
        TriageEngine engine = new TriageEngine();
        long end = sc.startMs();
        for (int w = 1; w <= 30; w++) {
            end += SimFleet.WINDOW_MS;
            engine.processWindow(end, fleet.window(end, sc.impairments(), rnd));
        }
        end += SimFleet.WINDOW_MS;
        List<TunnelWindow> partial = fleet.window(end, sc.impairments(), rnd).subList(0, 100);
        Views.WindowReport r = engine.processWindow(end, partial);
        assertThat(r.gap()).isTrue();
        assertThat(engine.incidents(true)).isEmpty();
    }

    @Test
    void duplicateChangeReportsAreIgnored() {
        TriageEngine engine = new TriageEngine();
        ConfigChange c = new ConfigChange("d1", "branch v2", "10.1.0.1", 100, 1000, 7, "mpls", List.of());
        engine.addChange(c);
        engine.addChange(c);
        assertThat(engine.status().changeGroups()).isEqualTo(1);
    }
}
