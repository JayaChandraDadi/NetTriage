package io.nettriage.core;

import io.nettriage.core.detect.TunnelState;
import io.nettriage.core.localize.Localizer;
import io.nettriage.core.model.Element;
import io.nettriage.core.model.Tunnel;

import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/** Everything the explanation layer may look at for one window. Read-only by convention. */
public record WindowView(
        long windowEnd,
        Topology topology,
        byte[] state,
        List<TunnelState> tunnels,
        Map<Element, ChangeGroup> changes,
        Map<String, DeviceStatus> devices) {

    /** [observed, degraded] over the given tunnel indices. */
    public int[] count(int[] cover) {
        return count(cover, i -> true);
    }

    public int[] count(int[] cover, IntPredicate filter) {
        int obs = 0;
        int bad = 0;
        for (int i : cover) {
            if (!filter.test(i) || state[i] == Localizer.EXCLUDED) {
                continue;
            }
            obs++;
            if (state[i] == Localizer.DEGRADED) {
                bad++;
            }
        }
        return new int[] {obs, bad};
    }

    public boolean degraded(int i) {
        return state[i] == Localizer.DEGRADED;
    }

    public Tunnel tunnel(int i) {
        return topology.tunnel(i);
    }

    /** Tunnels a cause covers, including changes, as of this window. */
    public int[] cover(Element e) {
        if (e.kind() == Element.Kind.CHANGE) {
            ChangeGroup g = changes.get(e);
            return g == null ? new int[0] : topology.coverOfRouters(g.appliedBy(windowEnd).keySet(), g.color());
        }
        return topology.cover(e);
    }
}
