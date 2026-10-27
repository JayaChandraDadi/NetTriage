package io.nettriage.service.api;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.Views;
import io.nettriage.core.incident.IncidentView;
import io.nettriage.core.tools.ActionRecord;
import io.nettriage.core.tools.ToolDefinition;
import io.nettriage.core.tools.TriageTools;
import io.nettriage.service.ActionService;
import io.nettriage.service.edgefleet.EdgeFleetSync;
import io.nettriage.service.ingest.KafkaHealthSource;
import io.nettriage.service.ingest.WindowAssembler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final TriageEngine engine;
    private final WindowAssembler assembler;
    private final ActionService actions;
    private final ObjectProvider<KafkaHealthSource> kafka;
    private final ObjectProvider<EdgeFleetSync> edgefleet;

    public ApiController(TriageEngine engine, WindowAssembler assembler, ActionService actions,
                         ObjectProvider<KafkaHealthSource> kafka, ObjectProvider<EdgeFleetSync> edgefleet) {
        this.engine = engine;
        this.assembler = assembler;
        this.actions = actions;
        this.kafka = kafka;
        this.edgefleet = edgefleet;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine", engine.status());
        m.put("ingest", assembler.stats());
        KafkaHealthSource k = kafka.getIfAvailable();
        m.put("kafka", k == null ? Map.of("enabled", false) : k.stats());
        EdgeFleetSync e = edgefleet.getIfAvailable();
        m.put("edgefleet", e == null ? Map.of("enabled", false) : e.stats());
        return m;
    }

    @GetMapping("/grid")
    public Views.Grid grid() {
        return engine.grid();
    }

    @GetMapping("/incidents")
    public List<IncidentView> incidents(@RequestParam(defaultValue = "true") boolean all) {
        return engine.incidents(all);
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<IncidentView> incident(@PathVariable String id) {
        return ResponseEntity.of(engine.incident(id));
    }

    @GetMapping("/incidents/{id}/compare")
    public Views.Comparison compare(@PathVariable String id, @RequestParam String element) {
        return engine.compare(id, element);
    }

    @GetMapping("/tunnels")
    public Views.TunnelQuery tunnels(@RequestParam(required = false) Integer site,
                                     @RequestParam(required = false) String color,
                                     @RequestParam(required = false) String region,
                                     @RequestParam(required = false) String hub,
                                     @RequestParam(required = false) Boolean degraded,
                                     @RequestParam(defaultValue = "200") int limit) {
        return engine.tunnels(new Views.TunnelFilter(site, color, region, hub, degraded), Math.min(limit, 2000));
    }

    @GetMapping("/changes")
    public List<Views.ChangeView> changes(@RequestParam(defaultValue = "1440") long sinceMinutes) {
        Long last = engine.status().lastWindowEnd();
        long now = last == null ? System.currentTimeMillis() : last;
        return engine.changes(now - sinceMinutes * 60_000L);
    }

    @GetMapping("/windows")
    public List<Views.WindowReport> windows() {
        return assembler.recent();
    }

    @GetMapping("/actions")
    public List<ActionRecord> actionLog() {
        return actions.records();
    }

    public record Decision(String approver, String reason) {
    }

    @PostMapping("/incidents/{id}/actions/{actionId}/approve")
    public ActionRecord approveAction(@PathVariable String id, @PathVariable String actionId, @RequestBody Decision d) {
        return actions.approve(id, actionId, d.approver());
    }

    @PostMapping("/actions/{requestId}/approve")
    public ActionRecord approveRequest(@PathVariable String requestId, @RequestBody Decision d) {
        return actions.approveRequest(requestId, d.approver());
    }

    @PostMapping("/actions/{requestId}/reject")
    public ActionRecord rejectRequest(@PathVariable String requestId, @RequestBody Decision d) {
        return actions.reject(requestId, d.approver(), d.reason());
    }

    @GetMapping("/tools")
    public List<ToolDefinition> tools() {
        return TriageTools.definitions();
    }
}
