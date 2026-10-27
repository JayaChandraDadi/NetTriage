package io.nettriage.agent;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.tools.ToolDefinition;
import io.nettriage.core.tools.TriageTools;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An on-call assistant: Claude, given the NetTriage tools, investigates the open
 * incidents and submits one structured diagnosis per incident. Each diagnosis is then
 * checked by {@link GroundingVerifier} against the engine.
 *
 * <p>The agent works through the same {@link TriageTools} the MCP server exposes, so
 * what it can see and do is exactly what any MCP client could. It can file remediation
 * requests but cannot approve them.
 */
public final class TriageAgent {

    static final String SYSTEM = """
            You are the on-call assistant for an SD-WAN overlay. NetTriage, your tool, has already run a \
            statistical root-cause analysis; your job is to check its reasoning, explain it plainly, and \
            say what to do. Work like a careful network engineer:
            - Start with list_incidents or fleet_overview, then get_incident for each open incident.
            - If an alternative explanation looks plausible, test it with compare_hypotheses or query_tunnels.
            - Every claim must come from tool output. Cite evidence by id (E1, E2...). Never state a \
            number that no tool returned.
            - If you disagree with the engine's root cause, say so and name the element you believe instead.
            - Only call request_remediation for a rollback when the incident's root cause is a config rollout \
            with HIGH confidence. It files a request for a person; it changes nothing.
            - Finish by calling submit_diagnosis once for each open incident, then stop.""";

    static final ToolDefinition SUBMIT = new ToolDefinition("submit_diagnosis", "Submit diagnosis",
            "Your final diagnosis for one incident. Call once per open incident.",
            Map.of("type", "object",
                    "properties", orderedMap(
                            "incident_id", Map.of("type", "string"),
                            "root_cause", Map.of("type", "string", "description", "Element id you believe is the root cause."),
                            "agrees_with_engine", Map.of("type", "boolean"),
                            "confidence", Map.of("type", "string", "enum", List.of("HIGH", "MEDIUM", "LOW")),
                            "summary", Map.of("type", "string", "description", "Two or three plain sentences for the on-call engineer."),
                            "evidence_ids", Map.of("type", "array", "items", Map.of("type", "string")),
                            "next_steps", Map.of("type", "array", "items", Map.of("type", "string"))),
                    "required", List.of("incident_id", "root_cause", "agrees_with_engine", "confidence", "summary",
                            "evidence_ids", "next_steps"),
                    "additionalProperties", false),
            true);

    /** One tool call the agent made, for the transcript. */
    public record Step(String tool, Map<String, Object> input, String result, boolean error) {
    }

    public record Result(List<Diagnosis> diagnoses, List<GroundingVerifier.Report> grounding, List<Step> steps,
                         int turns, long inputTokens, long outputTokens, String stopReason, String finalText) {
    }

    private final AnthropicClient client;
    private final TriageEngine engine;
    private final TriageTools tools;
    private final JsonMapper json;
    private final int maxTurns;

    public TriageAgent(AnthropicClient client, TriageEngine engine, TriageTools tools, JsonMapper json, int maxTurns) {
        this.client = client;
        this.engine = engine;
        this.tools = tools;
        this.json = json;
        this.maxTurns = maxTurns;
    }

    public Result run(String task) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("max_tokens", 4096);
        body.put("system", SYSTEM);
        ArrayNode toolDefs = body.putArray("tools");
        List<ToolDefinition> defs = new ArrayList<>(TriageTools.definitions());
        defs.add(SUBMIT);
        for (ToolDefinition d : defs) {
            ObjectNode t = toolDefs.addObject();
            t.put("name", d.name());
            t.put("description", d.description());
            t.set("input_schema", json.valueToTree(d.inputSchema()));
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", task);

        List<Diagnosis> diagnoses = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        StringBuilder transcript = new StringBuilder();
        long in = 0;
        long out = 0;
        int turns = 0;
        String stop = null;
        String finalText = "";

        while (turns < maxTurns) {
            turns++;
            JsonNode res = client.send(body);
            in += res.path("usage").path("input_tokens").asLong();
            out += res.path("usage").path("output_tokens").asLong();
            stop = res.path("stop_reason").asString();
            JsonNode content = res.path("content");
            messages.addObject().put("role", "assistant").set("content", content);

            ArrayNode results = json.createArrayNode();
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                String type = block.path("type").asString();
                if ("text".equals(type)) {
                    text.append(block.path("text").asString());
                } else if ("tool_use".equals(type)) {
                    String name = block.path("name").asString();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = json.convertValue(block.path("input"), Map.class);
                    String output;
                    boolean error = false;
                    if (SUBMIT.name().equals(name)) {
                        diagnoses.add(toDiagnosis(block.path("input")));
                        output = "Recorded.";
                    } else {
                        try {
                            output = json.writeValueAsString(tools.call(name, input, "ai-agent (" + client.model() + ")"));
                        } catch (IllegalArgumentException e) {
                            output = e.getMessage();
                            error = true;
                        }
                        transcript.append(output).append('\n');
                    }
                    steps.add(new Step(name, input, output.length() > 400 ? output.substring(0, 400) + "..." : output, error));
                    ObjectNode r = results.addObject();
                    r.put("type", "tool_result");
                    r.put("tool_use_id", block.path("id").asString());
                    r.put("content", output);
                    if (error) {
                        r.put("is_error", true);
                    }
                }
            }
            finalText = text.toString();
            if (results.isEmpty() || !"tool_use".equals(stop)) {
                break;
            }
            messages.addObject().put("role", "user").set("content", results);
        }

        List<GroundingVerifier.Report> grounding = new ArrayList<>();
        for (Diagnosis d : diagnoses) {
            grounding.add(GroundingVerifier.verify(d, engine, transcript.toString()));
        }
        return new Result(diagnoses, grounding, steps, turns, in, out, stop, finalText);
    }

    private static Diagnosis toDiagnosis(JsonNode n) {
        List<String> ev = new ArrayList<>();
        n.path("evidence_ids").forEach(x -> ev.add(x.asString()));
        List<String> next = new ArrayList<>();
        n.path("next_steps").forEach(x -> next.add(x.asString()));
        return new Diagnosis(n.path("incident_id").asString(), n.path("root_cause").asString(),
                n.path("agrees_with_engine").asBoolean(true), n.path("confidence").asString(),
                n.path("summary").asString(), ev, next);
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
