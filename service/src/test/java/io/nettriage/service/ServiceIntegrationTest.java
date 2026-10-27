package io.nettriage.service;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.nettriage.bench.Scenario;
import io.nettriage.bench.ScenarioFactory;
import io.nettriage.bench.SimFleet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service end to end over HTTP: telemetry in through the replay API, incidents out
 * through REST, the same incidents through a real MCP client, and the approval rules.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceIntegrationTest {

    @LocalServerPort
    int port;

    private RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private Scenario sc;
    private String incidentId;

    @BeforeAll
    void replayRollout() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build();
        sc = new ScenarioFactory(new SimFleet(200)).make("bad-rollout", 4);
        Replay.run(http, sc, sc.evalAt());
        JsonNode incidents = get("/api/incidents?all=false");
        incidentId = incidents.get(0).path("id").asString();
    }

    private JsonNode get(String uri) {
        return json.readTree(http.get().uri(uri).retrieve().body(String.class));
    }

    @Test
    void restReportsTheRolloutAsRootCause() {
        JsonNode inc = get("/api/incidents/" + incidentId);
        assertThat(inc.path("rootCause").asString()).isEqualTo(sc.truth().get(0));
        assertThat(inc.path("evidence").size()).isGreaterThanOrEqualTo(4);
        assertThat(inc.path("actions").get(0).path("kind").asString()).isEqualTo("ROLLBACK_DEPLOYMENT");

        JsonNode status = get("/api/status");
        assertThat(status.path("engine").path("tunnelsKnown").asInt()).isEqualTo(800);
        assertThat(status.path("engine").path("openIncidents").asInt()).isEqualTo(1);
        assertThat(get("/api/changes").get(0).path("routers").asInt()).isGreaterThan(0);
        assertThat(get("/api/grid").path("cells").size()).isEqualTo(9);
        assertThat(get("/api/tunnels?degraded=true&limit=5").path("degraded").asInt()).isGreaterThan(5);
    }

    @Test
    void mcpClientSeesTheSameIncidentAndCanOnlyRequestRemediation() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(json)).build();
        try (McpSyncClient mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10))
                .clientInfo(new McpSchema.Implementation("integration-test", "1.0")).build()) {
            McpSchema.InitializeResult init = mcp.initialize();
            assertThat(init.serverInfo().name()).isEqualTo("nettriage");
            assertThat(init.instructions()).contains("request_remediation only files a request");

            List<McpSchema.Tool> tools = mcp.listTools().tools();
            assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("fleet_overview", "list_incidents",
                    "get_incident", "query_tunnels", "compare_hypotheses", "list_config_changes", "request_remediation");
            assertThat(tools).filteredOn(t -> !t.name().equals("request_remediation"))
                    .allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).isTrue());

            McpSchema.CallToolResult inc = mcp.callTool(new McpSchema.CallToolRequest("get_incident",
                    Map.of("incident_id", incidentId)));
            String text = ((McpSchema.TextContent) inc.content().get(0)).text();
            assertThat(text).contains(sc.truth().get(0)).contains("\"E1\"");

            McpSchema.CallToolResult bad = mcp.callTool(new McpSchema.CallToolRequest("compare_hypotheses",
                    Map.of("incident_id", incidentId, "element", "nonsense")));
            assertThat(bad.isError()).isTrue();

            McpSchema.CallToolResult req = mcp.callTool(new McpSchema.CallToolRequest("request_remediation",
                    Map.of("incident_id", incidentId, "action_id", "A1", "justification", "E1, E2")));
            assertThat(((McpSchema.TextContent) req.content().get(0)).text()).contains("PENDING_APPROVAL");
        }
        JsonNode log = get("/api/actions");
        assertThat(log.get(0).path("requestedBy").asString()).isEqualTo("mcp:integration-test");
        assertThat(log.get(0).path("state").asString()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void approvalNeedsANamedPersonAndRollbackNeedsEdgeFleet() {
        var noName = http.post().uri("/api/incidents/" + incidentId + "/actions/A2/approve")
                .body(Map.of("approver", "")).retrieve().toEntity(String.class);
        assertThat(noName.getStatusCode().value()).isEqualTo(400);

        JsonNode advisory = json.readTree(http.post().uri("/api/incidents/" + incidentId + "/actions/A2/approve")
                .body(Map.of("approver", "jaya")).retrieve().body(String.class));
        assertThat(advisory.path("state").asString()).isEqualTo("APPROVED");
        assertThat(advisory.path("decidedBy").asString()).isEqualTo("jaya");

        JsonNode rollback = json.readTree(http.post().uri("/api/incidents/" + incidentId + "/actions/A1/approve")
                .body(Map.of("approver", "jaya")).retrieve().body(String.class));
        assertThat(rollback.path("state").asString()).isEqualTo("FAILED");
        assertThat(rollback.path("outcome").asString()).contains("EdgeFleet is not configured");
    }
}
