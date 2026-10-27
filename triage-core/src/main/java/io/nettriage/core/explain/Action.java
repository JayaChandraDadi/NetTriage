package io.nettriage.core.explain;

/**
 * A suggested response. Advisory actions are text for an operator. A rollback is an
 * executable call to EdgeFleet, and NetTriage only ever proposes it: a person has to
 * approve it before anything changes on the network.
 *
 * @param deploymentId target of a rollback; null for advisories
 */
public record Action(String id, Kind kind, String title, String detail, String deploymentId) {

    public enum Kind { ADVISORY, ROLLBACK_DEPLOYMENT }

    public boolean executable() {
        return kind == Kind.ROLLBACK_DEPLOYMENT;
    }
}
