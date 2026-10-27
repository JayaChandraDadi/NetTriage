package io.nettriage.service.api;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.Views;
import io.nettriage.core.model.ConfigChange;
import io.nettriage.service.ingest.TunnelHealthMessage;
import io.nettriage.service.ingest.WindowAssembler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Feeds telemetry and config changes over HTTP instead of Kafka and EdgeFleet: for
 * demos, tests and replaying recorded incidents. Disable with
 * {@code nettriage.replay-enabled=false}.
 */
@RestController
@RequestMapping("/api/replay")
@ConditionalOnProperty(name = "nettriage.replay-enabled", havingValue = "true", matchIfMissing = true)
public class ReplayController {

    private final WindowAssembler assembler;
    private final TriageEngine engine;

    public ReplayController(WindowAssembler assembler, TriageEngine engine) {
        this.assembler = assembler;
        this.engine = engine;
    }

    public record WindowBatch(long windowEnd, List<TunnelHealthMessage> tunnels) {
    }

    /** One closed window, diagnosed immediately. */
    @PostMapping("/window")
    public Views.WindowReport window(@RequestBody WindowBatch batch) {
        batch.tunnels().forEach(t -> assembler.accept(t.toWindow()));
        assembler.flushUpTo(batch.windowEnd());
        List<Views.WindowReport> recent = assembler.recent();
        return recent.isEmpty() ? null : recent.get(0);
    }

    @PostMapping("/changes")
    public Map<String, Object> changes(@RequestBody List<ConfigChange> changes) {
        changes.forEach(engine::addChange);
        return Map.of("accepted", changes.size());
    }
}
