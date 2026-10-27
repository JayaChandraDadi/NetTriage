package io.nettriage.service;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.explain.Action;
import io.nettriage.core.incident.IncidentView;
import io.nettriage.core.tools.ActionLedger;
import io.nettriage.core.tools.ActionRecord;
import io.nettriage.service.edgefleet.EdgeFleetClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.NoSuchElementException;

/**
 * The human side of remediation. Anyone, including an AI agent over MCP, may request an
 * action; only a named person approving it here makes anything happen. An approved
 * rollback is sent to EdgeFleet, whose own staged rollback then takes over.
 */
@Service
public class ActionService extends ActionLedger {

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);

    private final TriageEngine engine;
    private final ObjectProvider<EdgeFleetClient> edgefleet;

    public ActionService(TriageEngine engine, ObjectProvider<EdgeFleetClient> edgefleet) {
        this.engine = engine;
        this.edgefleet = edgefleet;
    }

    /** Approves one of an incident's proposed actions directly, filing the request if needed. */
    public ActionRecord approve(String incidentId, String actionId, String approver) {
        IncidentView inc = engine.incident(incidentId).orElseThrow(() -> new NoSuchElementException("no incident " + incidentId));
        Action action = inc.actions().stream().filter(a -> a.id().equals(actionId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("incident " + incidentId + " has no action " + actionId));
        ActionRecord r = request(inc.id(), action, approver(approver), "approved directly by " + approver);
        return decide(r.id(), approver, true, null);
    }

    public ActionRecord approveRequest(String requestId, String approver) {
        return decide(requestId, approver, true, null);
    }

    public ActionRecord reject(String requestId, String approver, String reason) {
        return decide(requestId, approver, false, reason);
    }

    private synchronized ActionRecord decide(String requestId, String approver, boolean approve, String reason) {
        String who = approver(approver);
        ActionRecord r = find(requestId).orElseThrow(() -> new NoSuchElementException("no request " + requestId));
        if (!r.state().equals("PENDING_APPROVAL")) {
            throw new IllegalArgumentException(requestId + " is already " + r.state());
        }
        long now = System.currentTimeMillis();
        if (!approve) {
            return replace(r.decided("REJECTED", who, now, reason == null ? "rejected" : reason));
        }
        if (!r.kind().equals(Action.Kind.ROLLBACK_DEPLOYMENT.name())) {
            return replace(r.decided("APPROVED", who, now, "Acknowledged; carry it out as described."));
        }
        EdgeFleetClient client = edgefleet.getIfAvailable();
        if (client == null) {
            return replace(r.decided("FAILED", who, now, "EdgeFleet is not configured (nettriage.edgefleet.url)."));
        }
        try {
            JsonNode dep = client.rollback(r.deploymentId());
            log.info("{} approved rollback of {} ({})", who, r.deploymentId(), requestId);
            return replace(r.decided("EXECUTED", who, now,
                    "EdgeFleet accepted the rollback; deployment is now " + dep.path("state").asString("?") + "."));
        } catch (RuntimeException e) {
            return replace(r.decided("FAILED", who, now, "EdgeFleet refused: " + e.getMessage()));
        }
    }

    private static String approver(String approver) {
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("approver is required: a person has to own this decision");
        }
        return approver.trim();
    }
}
