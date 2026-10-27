package io.nettriage.service.ingest;

import io.nettriage.service.NetTriageProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads WanPulse's {@code tunnel.health} topic. Starts from the latest offset: NetTriage
 * diagnoses the network as it is now and learns baselines from live traffic.
 */
@Component
@ConditionalOnProperty(name = "nettriage.kafka.enabled", havingValue = "true")
public class KafkaHealthSource implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaHealthSource.class);

    private final NetTriageProperties.Kafka cfg;
    private final WindowAssembler assembler;
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();
    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread thread;
    private volatile boolean running;

    public KafkaHealthSource(NetTriageProperties props, WindowAssembler assembler) {
        this.cfg = props.kafka();
        this.assembler = assembler;
    }

    @Override
    public void start() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.bootstrap(),
                ConsumerConfig.GROUP_ID_CONFIG, cfg.groupId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        consumer.subscribe(List.of(cfg.topic()));
        running = true;
        thread = Thread.ofVirtual().name("kafka-tunnel-health").start(this::loop);
        log.info("consuming {} from {}", cfg.topic(), cfg.bootstrap());
    }

    private void loop() {
        try {
            while (running) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    try {
                        assembler.accept(json.readValue(r.value(), TunnelHealthMessage.class).toWindow());
                        consumed.incrementAndGet();
                    } catch (RuntimeException e) {
                        malformed.incrementAndGet();
                    }
                }
            }
        } catch (WakeupException e) {
            // shutting down
        } catch (RuntimeException e) {
            log.error("kafka consumer stopped", e);
        } finally {
            consumer.close();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> stats() {
        return Map.of("topic", cfg.topic(), "bootstrap", cfg.bootstrap(), "consumed", consumed.get(),
                "malformed", malformed.get());
    }
}
