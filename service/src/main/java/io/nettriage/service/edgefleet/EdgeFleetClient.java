package io.nettriage.service.edgefleet;

import io.nettriage.service.NetTriageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The few EdgeFleet controller endpoints NetTriage reads, plus rollback. */
@Component
@ConditionalOnExpression("'${nettriage.edgefleet.url:}' != ''")
public class EdgeFleetClient {

    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public EdgeFleetClient(NetTriageProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(10_000);
        this.http = RestClient.builder().baseUrl(props.edgefleet().url()).requestFactory(f).build();
    }

    public JsonNode devices() {
        return get("/api/devices");
    }

    public JsonNode events(long afterId) {
        return get("/api/events?afterId=" + afterId + "&limit=500");
    }

    public JsonNode configAt(String serial, long version) {
        return get("/api/devices/" + serial + "/config?version=" + version);
    }

    /** Asks EdgeFleet to roll a deployment back; returns the deployment as EdgeFleet reports it. */
    public JsonNode rollback(String deploymentId) {
        String body = http.post().uri("/api/deployments/" + deploymentId + "/rollback").retrieve().body(String.class);
        return json.readTree(body == null ? "{}" : body);
    }

    private JsonNode get(String uri) {
        String body = http.get().uri(uri).retrieve().body(String.class);
        return json.readTree(body == null ? "null" : body);
    }
}
