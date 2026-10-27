package io.nettriage.agent;

import java.util.List;

/** What the agent concludes about one incident, as submitted through {@code submit_diagnosis}. */
public record Diagnosis(
        String incidentId,
        String rootCause,
        boolean agreesWithEngine,
        String confidence,
        String summary,
        List<String> evidenceIds,
        List<String> nextSteps) {
}
