package io.nettriage.core;

import io.nettriage.core.model.ConfigChange;
import io.nettriage.core.model.Element;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All routers that applied one rollout's change to the same transport, and when. */
public final class ChangeGroup {

    private final String deploymentId;
    private final String label;
    private final String color;
    private final List<String> diff;
    private final Map<String, Long> appliedAt = new LinkedHashMap<>();
    private final Map<String, Integer> siteOf = new LinkedHashMap<>();
    private long firstApplied = Long.MAX_VALUE;
    private long lastApplied = Long.MIN_VALUE;

    ChangeGroup(ConfigChange first) {
        String key = first.groupKey();
        this.deploymentId = key.substring(0, key.indexOf('|'));
        this.label = first.label();
        this.color = first.color();
        this.diff = first.diff();
    }

    void add(ConfigChange c) {
        appliedAt.merge(c.systemIp(), c.appliedAt(), Math::min);
        siteOf.put(c.systemIp(), c.siteId());
        firstApplied = Math.min(firstApplied, c.appliedAt());
        lastApplied = Math.max(lastApplied, c.appliedAt());
    }

    /** Routers that had applied the change by time {@code t}, with their apply times. */
    public Map<String, Long> appliedBy(long t) {
        Map<String, Long> out = new LinkedHashMap<>();
        appliedAt.forEach((ip, at) -> {
            if (at <= t) {
                out.put(ip, at);
            }
        });
        return out;
    }

    public Element element() {
        return Element.change(deploymentId, color);
    }

    public boolean isDeployment() {
        return !deploymentId.startsWith("adhoc-");
    }

    public String deploymentId() {
        return deploymentId;
    }

    public String label() {
        return label;
    }

    public String color() {
        return color;
    }

    public List<String> diff() {
        return diff;
    }

    public long firstApplied() {
        return firstApplied;
    }

    public long lastApplied() {
        return lastApplied;
    }

    public int siteOf(String systemIp) {
        return siteOf.getOrDefault(systemIp, -1);
    }

    public int routers() {
        return appliedAt.size();
    }
}
