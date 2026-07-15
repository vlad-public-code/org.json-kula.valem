package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sun.net.httpserver.HttpServer;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the server-effect loop through the real Spring shell: a mutation fires an
 * {@code http} effect against a local stub server, the executor folds the response back as a
 * mutation, and a derivation recomputes on the folded value. {@code allow-private-ips=true} lets the
 * guard call the {@code 127.0.0.1} stub.
 */
@SpringBootTest(properties = {"valem.effects.allow-private-ips=true", "valem.effects.allow-insecure-http=true"})
class HttpEffectExecutorIT {

    @Autowired ModelService service;
    @Autowired ObjectMapper mapper;
    @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private HttpServer stub;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger flakyHits = new AtomicInteger();

    @BeforeEach
    void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/rate", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{ \"rate\": 0.08, \"jurisdiction\": \"CA\" }".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        // Flaky endpoint: 503 on the first two calls, then 200 — exercises retry/backoff.
        stub.createContext("/flaky", exchange -> {
            int n = flakyHits.incrementAndGet();
            if (n < 3) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] body = "{ \"rate\": 0.2 }".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        port = stub.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        if (stub != null) stub.stop(0);
    }

    @Test
    void server_effect_calls_stub_and_folds_response_back() throws Exception {
        String id = "effect-it-" + System.nanoTime();
        String base = "http://127.0.0.1:" + port;
        ModelSpec spec = mapper.readValue("""
                {
                  "id": "%s", "schema": {},
                  "derivations": [
                    { "path": "$.order.tax", "expr": "order.subtotal * order.taxRate" }
                  ],
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null", "dedupeKey": "order.zip",
                      "request": { "method": "GET", "url": "%s/rate?zip={ order.zip }" },
                      "response": { "set": { "$.order.taxRate": "$response.rate" } },
                      "statusPath": "$.order.taxStatus" }
                  ]
                }
                """.formatted(id, base), ModelSpec.class);

        service.createModel(spec);
        service.mutate(id, Map.of("$.order.subtotal", mapper.getNodeFactory().numberNode(100.0)));

        // Firing edge: setting the zip triggers the effect (async, out-of-transaction).
        service.mutate(id, Map.of("$.order.zip", TextNode.valueOf("12345")));

        JsonNode taxRate = await(id, "$.order.taxRate");
        assertThat(taxRate.asDouble()).isEqualTo(0.08);

        // The folded value cascaded through the derivation.
        assertThat(service.getFieldValue(id, "$.order.tax").asDouble()).isEqualTo(8.0);
        // The status machine reached 'applied'.
        assertThat(service.getFieldValue(id, "$.order.taxStatus.phase").asText()).isEqualTo("applied");
        assertThat(hits.get()).isEqualTo(1);

        // A successful server-effect execution was timed (valem.effect.duration{kind,outcome}).
        var timer = meterRegistry.find("valem.effect.duration")
                .tag("kind", "server").tag("outcome", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);

        // Re-firing with the same edge value is deduped by the recorded status key — no new HTTP call.
        service.mutate(id, Map.of("$.order.zip", TextNode.valueOf("12345")));
        Thread.sleep(300);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void effect_retries_transient_failures_then_folds_back() throws Exception {
        String id = "effect-retry-" + System.nanoTime();
        String base = "http://127.0.0.1:" + port;
        ModelSpec spec = mapper.readValue("""
                {
                  "id": "%s", "schema": {},
                  "effects": [
                    { "id": "flaky", "executor": "server",
                      "trigger": "order.zip != null", "dedupeKey": "order.zip",
                      "request": { "method": "GET", "url": "%s/flaky?zip={ order.zip }" },
                      "response": { "set": { "$.order.taxRate": "$response.rate" } },
                      "statusPath": "$.order.taxStatus",
                      "policy": { "retries": 3, "backoff": "exponential" } }
                  ]
                }
                """.formatted(id, base), ModelSpec.class);

        service.createModel(spec);
        service.mutate(id, Map.of("$.order.zip", TextNode.valueOf("55555")));

        JsonNode taxRate = await(id, "$.order.taxRate");
        assertThat(taxRate.asDouble()).isEqualTo(0.2);
        assertThat(service.getFieldValue(id, "$.order.taxStatus.phase").asText()).isEqualTo("applied");
        // Two 503s + one 200.
        assertThat(flakyHits.get()).isEqualTo(3);
    }

    /** Polls a field until it is present (async fold-back), up to ~3s. */
    private JsonNode await(String id, String path) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            JsonNode v = service.getFieldValue(id, path);
            if (v != null && !v.isNull() && !v.isMissingNode()) return v;
            Thread.sleep(50);
        }
        throw new AssertionError("field " + path + " never appeared for model " + id);
    }
}
