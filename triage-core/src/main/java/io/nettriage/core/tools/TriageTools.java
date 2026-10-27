package io.nettriage.core.tools;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.Views;
import io.nettriage.core.explain.Action;
import io.nettriage.core.incident.IncidentView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The questions an AI agent may ask NetTriage, and the one thing it may ask for.
 *
 * <p>Six tools only read. The seventh, {@code request_remediation}, files a request that
 * a person must approve; no tool changes the network. Results are plain records that
 * serialize to JSON. Every claim an agent makes should trace back to an evidence id
 * ({@code E1}, {@code E2}...) or a number returned here.
 */
public final class TriageTools {

    public static final String INSTRUCTIONS = """
            NetTriage diagnoses degradations on an SD-WAN overlay (branch routers with tunnels to hubs over \
            mpls, biz-internet and lte). It learns each tunnel's normal behavior, finds the smallest set of \
            shared causes (a provider in a region, a site's circuit, a whole site, a hub, a config rollout) \
            that explains which tunnels are degraded and which are not, and records the evidence.
            Suggested flow: fleet_overview or list_incidents, then get_incident for the evidence (cite it by \
            id, E1, E2...), compare_hypotheses to test an alternative you suspect, query_tunnels to look at raw \
            numbers, list_config_changes to see recent rollouts. request_remediation only files a request for \
            a person to approve; it never changes the network. Do not state a number that no tool returned.""";

    private final TriageEngine engine;
    private final ActionLedger ledger;

    public TriageTools(TriageEngine engine, ActionLedger ledger) {
        this.engine = engine;
        this.ledger = ledger;
    }

    public static List<ToolDefinition> definitions() {
        return List.of(
                new ToolDefinition("fleet_overview", "Fleet overview",
                        "Current state of the whole overlay: how many tunnels are degraded by region and transport, "
                                + "by hub and transport, the open incidents with their root causes, and engine status.",
                        schema(Map.of(), List.of()), true),
                new ToolDefinition("list_incidents", "List incidents",
                        "Incidents with their root cause, confidence (HIGH, MEDIUM, LOW) and scope. Open only, unless "
                                + "include_resolved is true.",
                        schema(Map.of("include_resolved", prop("boolean", "Also return resolved incidents.")), List.of()),
                        true),
                new ToolDefinition("get_incident", "Get incident",
                        "Everything about one incident: headline, root cause, confidence and margin over the best "
                                + "alternative, numbered evidence (cite as E1, E2...), the alternatives that were ruled out "
                                + "and why, the signature of the degradation, timeline, and proposed actions.",
                        schema(Map.of("incident_id", prop("string", "Incident id, e.g. INC-0003.")), List.of("incident_id")),
                        true),
                new ToolDefinition("query_tunnels", "Query tunnels",
                        "Raw per-tunnel measurements against each tunnel's own baseline: loss, latency, jitter, health "
                                + "(DEGRADED, HEALTHY, UNKNOWN). Filter by site, transport color, region, hub. Returns "
                                + "counts for everything matched and up to `limit` rows, degraded first.",
                        schema(orderedProps(
                                "site_id", prop("integer", "Branch site id."),
                                "color", prop("string", "Transport color: mpls, biz-internet or lte."),
                                "region", prop("string", "Branch region, e.g. us-west."),
                                "hub", prop("string", "Hub system IP, e.g. 10.0.0.1."),
                                "degraded_only", prop("boolean", "Only degraded tunnels."),
                                "limit", prop("integer", "Rows to return, 1 to 100 (default 20).")), List.of()),
                        true),
                new ToolDefinition("compare_hypotheses", "Compare hypotheses",
                        "Tests an alternative explanation against an incident's root cause on the latest data: how many "
                                + "tunnels each covers and how many of those are degraded, and the log-likelihood margin "
                                + "(under about 4 means the two are hard to tell apart). Element ids look like site/102, "
                                + "site/102/lte, provider/biz-internet/us-west, carrier/lte, hub/10.0.0.1, "
                                + "hub/10.0.0.1/mpls, change/<deployment-id>/biz-internet.",
                        schema(orderedProps(
                                "incident_id", prop("string", "Incident id."),
                                "element", prop("string", "Alternative element id to test.")), List.of("incident_id", "element")),
                        true),
                new ToolDefinition("list_config_changes", "List config changes",
                        "Config rollouts applied by EdgeFleet recently: which routers, when, what changed, and how many "
                                + "of the touched tunnels are degraded now.",
                        schema(Map.of("since_minutes", prop("integer", "Look back this many minutes (default 60).")), List.of()),
                        true),
                new ToolDefinition("request_remediation", "Request remediation",
                        "Files a request to carry out one of an incident's proposed actions (see get_incident). This "
                                + "does NOT change the network: a person must approve it first. Include a justification "
                                + "that cites evidence ids.",
                        schema(orderedProps(
                                "incident_id", prop("string", "Incident id."),
                                "action_id", prop("string", "Action id from get_incident, e.g. A1."),
                                "justification", prop("string", "Why, citing evidence ids.")),
                                List.of("incident_id", "action_id", "justification")),
                        false));
    }

    /**
     * Runs a tool.
     *
     * @param requestedBy who is asking, recorded on remediation requests
     * @throws IllegalArgumentException for an unknown tool or bad arguments; the message
     *                                  is meant for the caller
     */
    public Object call(String name, Map<String, Object> args, String requestedBy) {
        Map<String, Object> a = args == null ? Map.of() : args;
        return switch (name) {
            case "fleet_overview" -> overview();
            case "list_incidents" -> engine.incidents(bool(a, "include_resolved")).stream().map(TriageTools::summary).toList();
            case "get_incident" -> trimmed(incident(str(a, "incident_id", true)));
            case "query_tunnels" -> engine.tunnels(new Views.TunnelFilter(integer(a, "site_id"), str(a, "color", false),
                    str(a, "region", false), str(a, "hub", false), bool(a, "degraded_only")),
                    Math.max(1, Math.min(100, integer(a, "limit") == null ? 20 : integer(a, "limit"))));
            case "compare_hypotheses" -> engine.compare(str(a, "incident_id", true), str(a, "element", true));
            case "list_config_changes" -> {
                Integer m = integer(a, "since_minutes");
                Long last = engine.status().lastWindowEnd();
                long now = last == null ? System.currentTimeMillis() : last;
                yield engine.changes(now - (m == null ? 60 : m) * 60_000L);
            }
            case "request_remediation" -> {
                IncidentView inc = incident(str(a, "incident_id", true));
                String actionId = str(a, "action_id", true);
                Action action = inc.actions().stream().filter(x -> x.id().equals(actionId)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("incident " + inc.id() + " has no action "
                                + actionId + "; its actions are " + inc.actions().stream().map(Action::id).toList()));
                ActionRecord r = ledger.request(inc.id(), action, requestedBy, str(a, "justification", true));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("request", r);
                out.put("note", "Filed for approval. Nothing has changed on the network; a person must approve "
                        + r.id() + " in NetTriage before " + (action.executable() ? "EdgeFleet runs the rollback." : "anyone acts on it."));
                yield out;
            }
            default -> throw new IllegalArgumentException("unknown tool " + name);
        };
    }

    private Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", engine.status());
        out.put("grid", engine.grid());
        out.put("openIncidents", engine.incidents(false).stream().map(TriageTools::summary).toList());
        return out;
    }

    private IncidentView incident(String id) {
        return engine.incident(id).orElseThrow(() -> new IllegalArgumentException("no incident " + id
                + "; list_incidents shows the ids"));
    }

    /** The full incident but only the latest stretch of its timeline, to keep results small. */
    private static IncidentView trimmed(IncidentView i) {
        List<IncidentView.Point> t = i.timeline();
        List<IncidentView.Point> tail = t.size() > 30 ? t.subList(t.size() - 30, t.size()) : t;
        return new IncidentView(i.id(), i.state(), i.rootCause(), i.rootCauseKind(), i.description(), i.headline(),
                i.confidence(), i.margin(), i.degradedNow(), i.coveredNow(), i.p(), i.peakDegraded(), i.sitesAffected(),
                i.sites().size() > 50 ? i.sites().subList(0, 50) : i.sites(), i.onsetAt(), i.openedAt(), i.lastSeenAt(),
                i.resolvedAt(), i.signature(), i.evidence(), i.alternatives(), i.actions(), i.history(), List.copyOf(tail));
    }

    public static Map<String, Object> summary(IncidentView i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("state", i.state());
        m.put("rootCause", i.rootCause());
        m.put("headline", i.headline());
        m.put("confidence", i.confidence());
        m.put("degradedNow", i.degradedNow());
        m.put("sitesAffected", i.sitesAffected());
        m.put("onsetAt", i.onsetAt());
        m.put("resolvedAt", i.resolvedAt());
        return m;
    }

    // ---- schema and argument helpers -----------------------------------------------------

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        if (!required.isEmpty()) {
            s.put("required", required);
        }
        s.put("additionalProperties", false);
        return s;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> orderedProps(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String str(Map<String, Object> a, String key, boolean required) {
        Object v = a.get(key);
        if (v == null || v.toString().isBlank()) {
            if (required) {
                throw new IllegalArgumentException("missing argument " + key);
            }
            return null;
        }
        return v.toString().trim();
    }

    private static Integer integer(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got " + v);
        }
    }

    private static boolean bool(Map<String, Object> a, String key) {
        Object v = a.get(key);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }
}
