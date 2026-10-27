package io.nettriage.core.tools;

/**
 * One remediation request and what became of it. Requests come from people or AI
 * agents; only a person can approve one, and only an approved rollback is executed.
 *
 * @param state PENDING_APPROVAL, REJECTED, EXECUTED or FAILED
 */
public record ActionRecord(
        String id,
        String incidentId,
        String actionId,
        String kind,
        String title,
        String deploymentId,
        String state,
        String requestedBy,
        String justification,
        long requestedAt,
        String decidedBy,
        Long decidedAt,
        String outcome) {

    public ActionRecord decided(String newState, String by, long at, String outcome) {
        return new ActionRecord(id, incidentId, actionId, kind, title, deploymentId, newState, requestedBy,
                justification, requestedAt, by, at, outcome);
    }
}
