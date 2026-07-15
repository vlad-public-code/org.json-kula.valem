package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.json_kula.valem.api.authz.EffectApprovalRegistry;
import org.json_kula.valem.api.effects.CompositeEffectExecutor;
import org.json_kula.valem.api.effects.EffectMetrics;
import org.json_kula.valem.api.effects.EgressGuard;
import org.json_kula.valem.api.effects.HttpEffectExecutor;
import org.json_kula.valem.api.effects.LinkEffectExecutor;
import org.json_kula.valem.api.effects.LlmEffectExecutor;
import org.json_kula.valem.api.effects.TimerEffectExecutor;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 — a link resolves and fires across an {@code http} repository: instance A's leaf write-links into
 * instance B (a separate {@link ModelService} fronted by a real JDK {@link HttpServer}), B computes,
 * and A folds the reply back. Proves {@link HttpModelRepository} + {@link HttpModelLink} end-to-end over
 * real HTTP, without Spring.
 */
class HttpCompositionTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private HttpServer server;
    private ModelService remote;   // instance B (the "web" repo)
    private ModelService local;    // instance A (drives the link)

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private ModelSpec spec(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    @Test
    void writeLinkFiresAcrossHttpRepository() throws Exception {
        // ── instance B: an aggregate with a derivation, exposed over a minimal REST facade ──
        remote = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        remote.createModel(spec("""
            { "id": "agg", "version": "1.0.0", "schema": {},
              "derivations": [ {"path": "$.doubled", "expr": "in * 2"} ] }
            """));
        String baseUrl = startRemoteFacade(remote);

        // ── instance A: a leaf whose only repository is the remote B, linked by coordinate ──
        local = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        EffectMetrics metrics = new EffectMetrics(new SimpleMeterRegistry());
        ModelResolver resolver = new ModelResolver(List.of(
                new HttpModelRepository("remote", baseUrl, null)));
        HttpEffectExecutor http = new HttpEffectExecutor(local, new EgressGuard(true, 1_048_576), metrics);
        LinkEffectExecutor link = new LinkEffectExecutor(local, resolver, metrics);
        LlmEffectExecutor llm = new LlmEffectExecutor(local, null, metrics);
        TimerEffectExecutor timer = new TimerEffectExecutor(local, metrics);
        var approvals = new EffectApprovalRegistry(EffectApprovalRegistry.Mode.APPROVE, local);
        local.setEffectExecutor(new CompositeEffectExecutor(http, link, llm, timer, approvals, local));

        local.createModel(spec("""
            { "id": "leaf", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "push", "executor": "server",
                "trigger": "subtotal >= 0", "dedupeKey": "subtotal",
                "target": { "ref": "agg", "path": "$.in" }, "body": "subtotal",
                "response": { "set": { "$.ack": "$response.doubled" } },
                "statusPath": "$.io.push"
              } ] }
            """));

        // Fire the cross-instance link.
        local.mutate("leaf", Map.of("$.subtotal", IntNode.valueOf(5)));

        // B received the write and recomputed; A folded B's derived value back.
        await(() -> remoteInt("agg", "$.in") == 5);
        await(() -> localInt("leaf", "$.ack") == 10);
        assertThat(remoteInt("agg", "$.doubled")).isEqualTo(10);
        assertThat(localText("leaf", "$.io.push.phase")).isEqualTo("applied");
    }

    // ── a minimal REST facade over instance B (the subset HttpModelRepository/Link call) ──

    private String startRemoteFacade(ModelService svc) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models/", exchange -> {
            try {
                String[] seg = exchange.getRequestURI().getPath().split("/");
                // /models/{id}/{tail...}: seg = ["","models",id,tail,(path)]
                String id = seg[2];
                String tail = seg.length > 3 ? seg[3] : "";
                String method = exchange.getRequestMethod();
                byte[] out;
                if (method.equals("GET") && tail.equals("spec")) {
                    out = mapper.writeValueAsBytes(svc.getSpec(id));
                } else if (method.equals("GET") && tail.equals("state") && seg.length == 4) {
                    out = mapper.writeValueAsBytes(svc.getState(id, null));
                } else if (method.equals("GET") && tail.equals("state")) {
                    JsonNode v = svc.getFieldValue(id, seg[4]);
                    if (v == null || v.isMissingNode()) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
                    out = mapper.writeValueAsBytes(v);
                } else if (method.equals("POST") && tail.equals("mutations")) {
                    Map<String, JsonNode> m = mapper.readValue(
                            exchange.getRequestBody().readAllBytes(),
                            mapper.getTypeFactory().constructMapType(Map.class, String.class, JsonNode.class));
                    svc.mutate(id, m);
                    out = "{}".getBytes(StandardCharsets.UTF_8);
                } else {
                    exchange.sendResponseHeaders(404, -1); exchange.close(); return;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                byte[] err = e.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private int remoteInt(String id, String path) { return asInt(remote.getFieldValue(id, path)); }
    private int localInt(String id, String path)  { return asInt(local.getFieldValue(id, path)); }
    private String localText(String id, String path) {
        JsonNode v = local.getFieldValue(id, path);
        return v == null || v.isNull() ? null : v.asText();
    }
    private static int asInt(JsonNode v) {
        return v == null || v.isNull() || v.isMissingNode() ? Integer.MIN_VALUE : v.asInt();
    }

    private static void await(BooleanSupplier cond) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("condition not met within timeout");
    }
}
