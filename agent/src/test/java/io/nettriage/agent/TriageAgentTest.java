package io.nettriage.agent;

import com.sun.net.httpserver.HttpServer;
import io.nettriage.bench.ScenarioFactory;
import io.nettriage.bench.SimFleet;
import io.nettriage.core.TriageEngine;
import io.nettriage.core.incident.IncidentView;
import io.nettriage.core.tools.ActionLedger;
import io.nettriage.core.tools.TriageTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the agent loop against a scripted stand-in for the Messages API, so the
 * protocol (tool definitions, tool_use, tool_result, errors, the final submission) and
 * the grounding checks are tested without a network or an API key.
 */
class TriageAgentTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> headers = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(List<String> responses) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> queue = new ArrayList<>(responses);
        server.createContext("/v1/messages", ex -> {
            requests.add(json.readTree(ex.getRequestBody().readAllBytes()));
            headers.add(ex.getRequestHeaders().getFirst("x-api-key") + "|" + ex.getRequestHeaders().getFirst("anthropic-version"));
            byte[] body = queue.remove(0).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String toolUse(String id, String name, String inputJson) {
        return """
                {"id":"msg","type":"message","role":"assistant","stop_reason":"tool_use",
                 "usage":{"input_tokens":100,"output_tokens":20},
                 "content":[{"type":"text","text":"Checking."},{"type":"tool_use","id":"%s","name":"%s","input":%s}]}"""
                .formatted(id, name, inputJson);
    }

    @Test
    void agentInvestigatesThroughToolsAndBadClaimsAreCaught() throws Exception {
        var sc = new ScenarioFactory(new SimFleet(200)).make("provider-brownout", 3);
        TriageEngine engine = AgentEval.replay(sc);
        IncidentView inc = engine.incidents(false).get(0);
        String good = String.format("%d of %d tunnels are degraded; other regions are fine.", inc.degradedNow(), inc.coveredNow());

        String base = start(List.of(
                toolUse("t1", "list_incidents", "{}"),
                toolUse("t2", "get_incident", "{\"incident_id\":\"" + inc.id() + "\"}"),
                toolUse("t3", "compare_hypotheses", "{\"incident_id\":\"" + inc.id() + "\",\"element\":\"bogus\"}"),
                toolUse("t4", "submit_diagnosis", json.writeValueAsString(java.util.Map.of(
                        "incident_id", inc.id(), "root_cause", inc.rootCause(), "agrees_with_engine", true,
                        "confidence", "HIGH", "summary", good, "evidence_ids", List.of("E1", "E2"),
                        "next_steps", List.of("Open a ticket"))))
                        .replace("\"stop_reason\":\"tool_use\"", "\"stop_reason\":\"tool_use\""),
                toolUse("t5", "submit_diagnosis", json.writeValueAsString(java.util.Map.of(
                        "incident_id", inc.id(), "root_cause", "hub/10.0.0.9", "agrees_with_engine", true,
                        "confidence", "HIGH", "summary", "12345 tunnels are down.", "evidence_ids", List.of("E99"),
                        "next_steps", List.of()))),
                """
                {"id":"msg","type":"message","role":"assistant","stop_reason":"end_turn",
                 "usage":{"input_tokens":50,"output_tokens":10},"content":[{"type":"text","text":"Done."}]}""")); 

        AnthropicClient client = new AnthropicClient(base, "test-key", "claude-test", json);
        ActionLedger ledger = new ActionLedger();
        TriageAgent.Result r = new TriageAgent(client, engine, new TriageTools(engine, ledger), json, 10)
                .run("Investigate.");

        assertThat(requests).hasSize(6);
        assertThat(headers.get(0)).isEqualTo("test-key|2023-06-01");
        JsonNode first = requests.get(0);
        assertThat(first.path("model").asString()).isEqualTo("claude-test");
        List<String> toolNames = new ArrayList<>();
        first.path("tools").forEach(t -> toolNames.add(t.path("name").asString()));
        assertThat(toolNames).contains("get_incident", "request_remediation", "submit_diagnosis");

        JsonNode secondTurn = requests.get(1).path("messages");
        JsonNode toolResult = secondTurn.get(secondTurn.size() - 1).path("content").get(0);
        assertThat(toolResult.path("type").asString()).isEqualTo("tool_result");
        assertThat(toolResult.path("tool_use_id").asString()).isEqualTo("t1");
        assertThat(toolResult.path("content").asString()).contains(inc.id());

        JsonNode fourthTurn = requests.get(3).path("messages");
        JsonNode err = fourthTurn.get(fourthTurn.size() - 1).path("content").get(0);
        assertThat(err.path("is_error").asBoolean()).isTrue();
        assertThat(err.path("content").asString()).contains("not an element id");

        assertThat(r.turns()).isEqualTo(6);
        assertThat(r.inputTokens()).isEqualTo(550);
        assertThat(r.diagnoses()).hasSize(2);
        assertThat(r.grounding().get(0).grounded()).isTrue();
        GroundingVerifier.Report bad = r.grounding().get(1);
        assertThat(bad.grounded()).isFalse();
        assertThat(bad.unknownEvidenceIds()).containsExactly("E99");
        assertThat(bad.unsupportedNumbers()).contains("12345");
        assertThat(bad.rootCauseMatchesEngine()).isFalse();
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void remediationRequestIsFiledButNeverExecuted() {
        var sc = new ScenarioFactory(new SimFleet(200)).make("bad-rollout", 4);
        TriageEngine engine = AgentEval.replay(sc);
        IncidentView inc = engine.incidents(false).get(0);
        ActionLedger ledger = new ActionLedger();
        Object out = new TriageTools(engine, ledger).call("request_remediation", java.util.Map.of(
                "incident_id", inc.id(), "action_id", "A1", "justification", "E1 and E2"), "ai-agent");
        assertThat(ledger.records()).singleElement().satisfies(rec -> {
            assertThat(rec.state()).isEqualTo("PENDING_APPROVAL");
            assertThat(rec.kind()).isEqualTo("ROLLBACK_DEPLOYMENT");
            assertThat(rec.requestedBy()).isEqualTo("ai-agent");
        });
        assertThat(out.toString()).contains("Nothing has changed on the network");
    }
}
