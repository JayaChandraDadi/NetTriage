package io.nettriage.agent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal client for the Claude Messages API: one POST per turn, with retries on rate
 * limits and overload. No SDK, so the request and response shapes stay visible.
 */
public final class AnthropicClient {

    public static final String DEFAULT_MODEL = "claude-sonnet-5-5";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String model;

    public AnthropicClient(String baseUrl, String apiKey, String model, JsonMapper json) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/messages");
        this.apiKey = apiKey;
        this.model = model;
        this.json = json;
    }

    /** Reads ANTHROPIC_API_KEY, and optionally ANTHROPIC_BASE_URL and NETTRIAGE_AGENT_MODEL. */
    public static AnthropicClient fromEnvironment(JsonMapper json) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Set ANTHROPIC_API_KEY to run the agent.");
        }
        String base = System.getenv().getOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        String model = System.getenv().getOrDefault("NETTRIAGE_AGENT_MODEL", DEFAULT_MODEL);
        return new AnthropicClient(base, key, model, json);
    }

    public String model() {
        return model;
    }

    /** Sends one request body (model is filled in) and returns the parsed response. */
    public JsonNode send(ObjectNode body) throws IOException, InterruptedException {
        body.put("model", model);
        String payload = json.writeValueAsString(body);
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(120))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                return json.readTree(res.body());
            }
            last = new IOException("Messages API returned " + res.statusCode() + ": " + res.body());
            if (res.statusCode() != 429 && res.statusCode() != 529 && res.statusCode() < 500) {
                break;
            }
            Thread.sleep(1000L << attempt);
        }
        throw last;
    }
}
