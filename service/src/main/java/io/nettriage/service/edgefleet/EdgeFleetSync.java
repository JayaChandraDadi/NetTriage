package io.nettriage.service.edgefleet;

import io.nettriage.core.DeviceStatus;
import io.nettriage.core.TriageEngine;
import io.nettriage.core.model.ConfigChange;
import io.nettriage.service.NetTriageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Follows EdgeFleet's event log. Every config a router applies and reports healthy
 * becomes a {@link ConfigChange}: NetTriage fetches the version before and after, diffs
 * them to see which transport the change touches, and keeps the rollout it belongs to.
 * Control-session events become device status, which corroborates site-level causes.
 */
@Component
@ConditionalOnExpression("'${nettriage.edgefleet.url:}' != ''")
public class EdgeFleetSync {

    private static final Logger log = LoggerFactory.getLogger(EdgeFleetSync.class);

    private record Router(String systemIp, int siteId) {
    }

    private final EdgeFleetClient client;
    private final TriageEngine engine;
    private final long devicesIntervalMs;
    private final Map<String, Router> routers = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private long lastEventId;
    private long devicesLoadedAt;
    private long changes;
    private String lastError;
    private Long lastOkAt;

    public EdgeFleetSync(EdgeFleetClient client, TriageEngine engine, NetTriageProperties props) {
        this.client = client;
        this.engine = engine;
        this.devicesIntervalMs = props.edgefleet().devicesInterval().toMillis();
    }

    @Scheduled(fixedDelayString = "${nettriage.edgefleet.poll-interval:2s}")
    public synchronized void poll() {
        try {
            if (System.currentTimeMillis() - devicesLoadedAt > devicesIntervalMs) {
                loadDevices();
            }
            JsonNode events = client.events(lastEventId);
            for (JsonNode e : events) {
                lastEventId = Math.max(lastEventId, e.path("id").asLong());
                handle(e);
            }
            lastOkAt = System.currentTimeMillis();
            lastError = null;
        } catch (RuntimeException e) {
            if (lastError == null) {
                log.warn("EdgeFleet poll failed: {}", e.getMessage());
            }
            lastError = e.getMessage();
        }
    }

    private void loadDevices() {
        for (JsonNode d : client.devices()) {
            routers.put(d.path("serial").asString(), new Router(d.path("systemIp").asString(), d.path("siteId").asInt()));
        }
        devicesLoadedAt = System.currentTimeMillis();
    }

    private void handle(JsonNode e) {
        String kind = e.path("kind").asString();
        String serial = e.path("serial").asString(null);
        JsonNode d = e.path("detail");
        long at = Instant.parse(e.path("at").asString()).toEpochMilli();
        if (serial == null) {
            return;
        }
        Router router = routers.get(serial);
        if (router == null) {
            loadDevices();
            router = routers.get(serial);
            if (router == null) {
                return;
            }
        }
        switch (kind) {
            case "config.assigned" -> labels.put(serial + "#" + d.path("version").asLong(),
                    d.path("reason").asString() + "|" + d.path("template").asString(""));
            case "config.applied" -> applied(serial, router, d, at, e.path("deploymentId").asString(null));
            case "session.connected" -> engine.setDeviceStatus(new DeviceStatus(router.systemIp(), true, at));
            case "session.disconnected" -> engine.setDeviceStatus(new DeviceStatus(router.systemIp(), false, at));
            default -> {
            }
        }
    }

    private void applied(String serial, Router router, JsonNode d, long at, String deploymentId) {
        long version = d.path("version").asLong();
        String label = labels.getOrDefault(serial + "#" + version, "|");
        String reason = label.substring(0, label.indexOf('|'));
        String template = label.substring(label.indexOf('|') + 1);
        if (version <= 1 || "day0".equals(reason) || "drift-remediation".equals(reason)) {
            return; // first config, or restoring what was intended: not a new change
        }
        ConfigDiff.Result diff = ConfigDiff.diff(client.configAt(serial, version - 1), client.configAt(serial, version));
        if (diff.lines().isEmpty()) {
            return;
        }
        String name = !template.isEmpty() ? template : "config v" + version;
        if ("rollback".equals(reason)) {
            name = "rollback of " + name;
        }
        engine.addChange(new ConfigChange(deploymentId, name, router.systemIp(), router.siteId(), at, version,
                diff.color(), diff.lines()));
        changes++;
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("routers", routers.size());
        m.put("lastEventId", lastEventId);
        m.put("changesRecorded", changes);
        m.put("lastOkAt", lastOkAt);
        m.put("lastError", lastError);
        return m;
    }
}
