package io.nettriage.agent;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.explain.Evidence;
import io.nettriage.core.incident.IncidentView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an agent's diagnosis against what the engine actually knows, so a fluent but
 * invented write-up is caught before anyone acts on it:
 *
 * <ul>
 *   <li>every cited evidence id exists on that incident;</li>
 *   <li>the root cause is the engine's, or the agent says it disagrees and names an
 *       element the engine considered;</li>
 *   <li>every number in the summary appears in some tool result the agent received.</li>
 * </ul>
 */
public final class GroundingVerifier {

    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\d+)?");

    public record Report(String incidentId, boolean incidentExists, List<String> unknownEvidenceIds,
                         boolean citesEvidence, boolean rootCauseMatchesEngine, boolean rootCauseKnownToEngine,
                         List<String> unsupportedNumbers, boolean grounded) {
    }

    private GroundingVerifier() {
    }

    public static Report verify(Diagnosis d, TriageEngine engine, String toolTranscript) {
        Optional<IncidentView> inc = engine.incident(d.incidentId());
        if (inc.isEmpty()) {
            return new Report(d.incidentId(), false, d.evidenceIds(), false, false, false, List.of(), false);
        }
        IncidentView i = inc.get();
        Set<String> ids = new TreeSet<>();
        i.evidence().stream().map(Evidence::id).forEach(ids::add);
        List<String> unknown = d.evidenceIds().stream().filter(e -> !ids.contains(e)).toList();
        boolean matches = i.rootCause().equals(d.rootCause());
        boolean known = matches || i.alternatives().stream().anyMatch(a -> a.element().equals(d.rootCause()));

        List<String> unsupported = new ArrayList<>();
        Matcher m = NUMBER.matcher(d.summary() == null ? "" : d.summary());
        while (m.find()) {
            String n = m.group();
            if (!appears(n, toolTranscript)) {
                unsupported.add(n);
            }
        }
        boolean cites = !d.evidenceIds().isEmpty();
        boolean grounded = unknown.isEmpty() && cites && (matches || !d.agreesWithEngine() && known) && unsupported.isEmpty();
        return new Report(i.id(), true, unknown, cites, matches, known, unsupported, grounded);
    }

    /** A number is supported if it appears in tool output, allowing a rounded form of a decimal. */
    private static boolean appears(String n, String transcript) {
        Pattern p = Pattern.compile("(?<![\\w.])" + Pattern.quote(n) + "(?![\\d])");
        if (p.matcher(transcript).find()) {
            return true;
        }
        if (!n.contains(".")) {
            Pattern rounded = Pattern.compile("(?<![\\w.])" + Pattern.quote(n) + "\\.\\d");
            return rounded.matcher(transcript).find();
        }
        return false;
    }
}
