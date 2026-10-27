package io.nettriage.bench;

import io.nettriage.core.model.ConfigChange;
import io.nettriage.core.model.Tunnel;

import java.util.List;
import java.util.function.Predicate;

/**
 * One benchmark case: faults with known root causes, optional config rollouts, and when
 * to judge the diagnosis.
 *
 * @param truth   element ids a perfect diagnosis names at {@code evalAt}; empty means
 *                nothing is wrong and any incident is a false alarm
 * @param evalAt  epoch ms at which the diagnosis is judged
 */
public record Scenario(
        String type,
        long seed,
        long startMs,
        int windows,
        List<Fault> faults,
        List<ConfigChange> changes,
        List<String> truth,
        long faultStart,
        long faultEnd,
        long evalAt,
        String note) {

    /**
     * Degradation on matching tunnels between two instants. {@code since} lets a fault
     * start per router (a bad config takes effect when each router applies it).
     */
    public record Fault(String name, Predicate<Tunnel> match, SimFleet.Impairment impairment, long start, long end,
                        java.util.function.ToLongFunction<Tunnel> since) {

        public boolean active(Tunnel t, long time) {
            if (time < start || time >= end || !match.test(t)) {
                return false;
            }
            return since == null || time >= since.applyAsLong(t);
        }
    }

    public SimFleet.ImpairmentSource impairments() {
        return (t, time) -> {
            SimFleet.Impairment imp = SimFleet.Impairment.NONE;
            for (Fault f : faults) {
                if (f.active(t, time)) {
                    imp = imp.plus(f.impairment());
                }
            }
            return imp;
        };
    }
}
