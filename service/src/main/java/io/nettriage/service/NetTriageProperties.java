package io.nettriage.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param windowQuiet   a telemetry window is diagnosed once no record for it has arrived
 *                      for this long; WanPulse emits each window's records in one burst
 * @param replayEnabled accept telemetry and changes over REST, for demos and tests
 */
@ConfigurationProperties("nettriage")
public record NetTriageProperties(
        @DefaultValue("2s") Duration windowQuiet,
        @DefaultValue Kafka kafka,
        @DefaultValue EdgeFleet edgefleet,
        @DefaultValue("true") boolean replayEnabled) {

    public record Kafka(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("localhost:9092") String bootstrap,
            @DefaultValue("tunnel.health") String topic,
            @DefaultValue("nettriage") String groupId) {
    }

    /** @param url EdgeFleet controller base URL; empty disables the integration */
    public record EdgeFleet(
            @DefaultValue("") String url,
            @DefaultValue("2s") Duration pollInterval,
            @DefaultValue("30s") Duration devicesInterval) {

        public boolean enabled() {
            return url != null && !url.isBlank();
        }
    }
}
