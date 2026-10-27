package io.nettriage.core.model;

import java.util.List;

/**
 * A config version that one router applied and reported healthy, as recorded by
 * EdgeFleet. Its own health check passed; whether the network stayed healthy
 * afterwards is the question NetTriage answers.
 *
 * @param deploymentId    rollout this change belongs to; null for a one-off change
 * @param label           human label, e.g. {@code branch v4}
 * @param systemIp        router that applied it
 * @param appliedAt       epoch ms when the router confirmed the new version
 * @param color           the single transport color the change touches, or null when it
 *                        touches several transports or shared settings (BFD, system)
 * @param diff            changed paths, e.g. {@code ~ transports[color=biz-internet].mtu: 1500 -> 1380}
 */
public record ConfigChange(
        String deploymentId,
        String label,
        String systemIp,
        int siteId,
        long appliedAt,
        long version,
        String color,
        List<String> diff) {

    public ConfigChange {
        diff = diff == null ? List.of() : List.copyOf(diff);
    }

    /** Groups changes that are the same rollout touching the same thing. */
    public String groupKey() {
        return (deploymentId == null ? "adhoc-" + systemIp + "-v" + version : deploymentId)
                + "|" + (color == null ? "*" : color);
    }
}
