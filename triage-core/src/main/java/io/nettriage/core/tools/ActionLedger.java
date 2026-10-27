package io.nettriage.core.tools;

import io.nettriage.core.explain.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Remediation requests awaiting a person. Requesting never changes the network; it only
 * puts the action in front of an approver with the requester's justification attached.
 */
public class ActionLedger {

    private final List<ActionRecord> records = new ArrayList<>();
    private int next = 1;

    public synchronized ActionRecord request(String incidentId, Action action, String requestedBy, String justification) {
        for (ActionRecord r : records) {
            if (r.incidentId().equals(incidentId) && r.actionId().equals(action.id())
                    && r.state().equals("PENDING_APPROVAL")) {
                return r;
            }
        }
        ActionRecord r = new ActionRecord(String.format("REQ-%04d", next++), incidentId, action.id(), action.kind().name(),
                action.title(), action.deploymentId(), "PENDING_APPROVAL", requestedBy, justification,
                System.currentTimeMillis(), null, null, null);
        records.add(0, r);
        return r;
    }

    public synchronized List<ActionRecord> records() {
        return List.copyOf(records);
    }

    public synchronized Optional<ActionRecord> find(String id) {
        return records.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    protected synchronized ActionRecord replace(ActionRecord updated) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equals(updated.id())) {
                records.set(i, updated);
                return updated;
            }
        }
        throw new IllegalArgumentException("no request " + updated.id());
    }
}
