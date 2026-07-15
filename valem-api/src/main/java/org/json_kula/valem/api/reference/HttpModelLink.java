package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.ModelCoordinate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The {@code http} (cross-instance) {@link ModelLink}: drives a model hosted by another Valem
 * instance over REST (references design §4.1). A write-link POSTs {@code /models/{id}/mutations} then
 * reads back the target's state as the fold-back reply; a read-link GETs {@code /models/{id}/state/{path}}.
 * The locator is a server-configured trusted endpoint, so repository calls are not subject to the
 * spec-provided-URL egress guard.
 */
final class HttpModelLink implements ModelLink {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String targetId;
    private final ModelCoordinate coordinate;

    HttpModelLink(HttpClient http, ObjectMapper mapper, String baseUrl, String apiKey,
                  String targetId, ModelCoordinate coordinate) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.targetId = targetId;
        this.coordinate = coordinate;
    }

    @Override
    public ModelCoordinate coordinate() {
        return coordinate;
    }

    @Override
    public MutationReply mutate(Map<String, JsonNode> pathValues) {
        try {
            String body = mapper.writeValueAsString(pathValues);
            HttpResponse<byte[]> resp = send(HttpRequest.newBuilder(
                    URI.create(baseUrl + "/models/" + enc(targetId) + "/mutations"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)));
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("target_rejected: HTTP " + resp.statusCode()
                        + " " + new String(resp.body(), StandardCharsets.UTF_8));
            }
            JsonNode reply = getState();
            return new MutationReply(reply, List.of());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("transport_error: " + e, e);
        }
    }

    @Override
    public JsonNode getField(String path) {
        try {
            HttpResponse<byte[]> resp = send(HttpRequest.newBuilder(
                    URI.create(baseUrl + "/models/" + enc(targetId) + "/state/" + enc(path))).GET());
            if (resp.statusCode() == 404) return mapper.nullNode();   // absent path folds a null (§4.2)
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("transport_error: HTTP " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("transport_error: " + e, e);
        }
    }

    private JsonNode getState() throws Exception {
        HttpResponse<byte[]> resp = send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/models/" + enc(targetId) + "/state")).GET());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("transport_error reading target state: HTTP " + resp.statusCode());
        }
        return mapper.readTree(resp.body());
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder rb) throws Exception {
        rb.timeout(Duration.ofSeconds(10));
        if (apiKey != null && !apiKey.isBlank()) rb.header("Authorization", "Bearer " + apiKey);
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String enc(String s) {
        // Encode a path segment but keep the address-friendly characters the routes expect.
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
