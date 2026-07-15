package org.json_kula.valem.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json_kula.valem.client.ValemClient;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link RemoteModelOperations} through a real {@link ValemClient} against a canned
 * {@link HttpServer} stub — no Spring, no live engine — to prove the create → mutate → get_state
 * round trip and the error-shape parity (§7.4 / R2.5).
 */
class RemoteModelOperationsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private RemoteModelOperations remoteAgainst(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler);
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        return new RemoteModelOperations(new ValemClient(baseUrl), mapper);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void roundTripsCreateMutateGetState() throws IOException {
        RemoteModelOperations remote = remoteAgainst(ex -> {
            String route = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            switch (route) {
                case "POST /models" ->
                        respond(ex, 201, "{\"id\":\"m1\",\"status\":\"created\"}");
                case "POST /models/m1/mutations" ->
                        respond(ex, 200, """
                            {"success":true,
                             "mutatedPaths":["$.qty"],
                             "derivedUpdated":["$.total"],
                             "flaggedConstraints":[],
                             "dispatchedEffects":[{"effectId":"e1","emit":"notify","payload":{"x":1}}],
                             "traces":[],
                             "viewDelta":null}""");
                case "GET /models/m1/state" ->
                        respond(ex, 200, "{\"qty\":3,\"total\":30}");
                default -> respond(ex, 404, "{\"detail\":\"no route: " + route + "\"}");
            }
        });

        ModelSpec spec = mapper.readValue(
                "{\"id\":\"m1\",\"version\":\"1.0.0\",\"schema\":{\"type\":\"object\"}}", ModelSpec.class);
        remote.createModel(spec);   // must not throw

        ModelService.MutationOutcome outcome = remote.mutate("m1", Map.of("$.qty", mapper.valueToTree(3)));
        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.result().mutatedPaths()).containsExactly("$.qty");
        assertThat(outcome.result().derivedUpdated()).containsExactly("$.total");
        assertThat(outcome.result().metaUpdated()).isEmpty();   // not on the wire
        assertThat(outcome.result().dispatchedEffects()).hasSize(1);
        assertThat(outcome.result().dispatchedEffects().getFirst().effectId()).isEqualTo("e1");

        ObjectNode state = remote.getState("m1", null);
        assertThat(state.get("qty").asInt()).isEqualTo(3);
        assertThat(state.get("total").asInt()).isEqualTo(30);
    }

    @Test
    void mapsNotFoundToMessageParity() throws IOException {
        RemoteModelOperations remote = remoteAgainst(ex ->
                respond(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
                        + "\"detail\":\"Model not found: nope\"}"));

        assertThatThrownBy(() -> remote.getState("nope", null))
                .isInstanceOf(RemoteOperationException.class)
                .hasMessage("Model not found: nope");
    }

    @Test
    void rebuildsConstraintViolationForStructuredParity() throws IOException {
        RemoteModelOperations remote = remoteAgainst(ex ->
                respond(ex, 409, "{\"error\":\"Constraint violation\",\"violations\":"
                        + "[{\"constraintId\":\"c1\",\"message\":\"too big\",\"policy\":\"rollback\"}]}"));

        assertThatThrownBy(() -> remote.mutate("m1", Map.of("$.qty", mapper.valueToTree(999))))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class)
                .satisfies(e -> {
                    var violations = ((ConstraintEvaluator.ConstraintViolationException) e).violations();
                    assertThat(violations).hasSize(1);
                    assertThat(violations.getFirst().constraintId()).isEqualTo("c1");
                    assertThat(violations.getFirst().message()).isEqualTo("too big");
                });
    }

    @Test
    void uploadsAndDownloadsBlobs() throws IOException {
        byte[] payload = "hello-blob".getBytes(StandardCharsets.UTF_8);
        RemoteModelOperations remote = remoteAgainst(ex -> {
            String route = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            if (route.equals("POST /blobs")) {
                drain(ex.getRequestBody());
                respond(ex, 201, "{\"$blobId\":\"sha256:abc\",\"$mediaType\":\"text/plain\",\"$bytes\":10}");
            } else if (route.startsWith("GET /blobs/")) {
                byte[] bytes = "hello-blob".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            } else {
                respond(ex, 404, "{\"detail\":\"no route\"}");
            }
        });

        var ref = remote.uploadBlob(new java.io.ByteArrayInputStream(payload), "text/plain");
        assertThat(ref.blobId()).isEqualTo("sha256:abc");
        assertThat(ref.bytes()).isEqualTo(10);

        try (InputStream in = remote.downloadBlob("sha256:abc")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello-blob");
        }
    }

    private static void drain(InputStream in) throws IOException {
        in.readAllBytes();
    }
}
