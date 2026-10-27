package io.nettriage.core.detect;

/** A tunnel's smoothed state in one window. */
public enum Health {
    /** Not enough history or data to judge, or the last windows disagree. */
    UNKNOWN,
    HEALTHY,
    /** Degraded in at least two of the last three windows. */
    DEGRADED
}
