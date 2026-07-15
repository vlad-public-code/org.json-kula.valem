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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for keyed fold-back resolution: a stale in-flight response must not overwrite a
 * newer input (supersession + trailing re-fire), and a timer whose precondition changed must cancel
 * rather than apply its (now wrong) fold-back.
 */
@SpringBootTest(properties = {"valem.effects.allow-private-ips=true", "valem.effects.allow-insecure-http=true"})
class EffectFoldbackResolutionIT {

    @Autowired ModelService service;
    @Autowired ObjectMapper mapper;

    private HttpServer stub;
    private int port;

    @BeforeEach
    void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A deliberately SLOW SLA service: sleeps, then returns a category-specific value. The delay
        // opens the in-flight window in which the input can change.
        stub.createContext("/lookup", exchange -> {
            String query = exchange.getRequestURI().getQuery();          // category=A
            String category = query != null && query.startsWith("category=") ? query.substring(9) : "";
            try { Thread.sleep(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            int minutes = "A".equals(category) ? 10 : 20;
            byte[] body = ("{ \"slaMinutes\": " + minutes + " }").getBytes(StandardCharsets.UTF_8);
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
    void stale_response_is_superseded_by_the_latest_input() throws Exception {
        String id = "supersede-" + System.nanoTime();
        ModelSpec spec = mapper.readValue("""
                {
                  "id": "%s", "schema": {},
                  "effects": [
                    { "id": "sla", "executor": "server",
                      "trigger": "ticket.category != null", "dedupeKey": "ticket.category",
                      "request": { "method": "GET", "url": "http://127.0.0.1:%d/lookup?category={ ticket.category }" },
                      "response": { "set": { "$.ticket.slaMinutes": "$response.slaMinutes" } },
                      "statusPath": "$.ticket.ioSla" }
                  ]
                }
                """.formatted(id, port), ModelSpec.class);
        service.createModel(spec);

        service.mutate(id, Map.of("$.ticket.category", TextNode.valueOf("A")));  // fires, stub sleeps 400ms
        Thread.sleep(80);                                                        // ensure the A call is in flight
        service.mutate(id, Map.of("$.ticket.category", TextNode.valueOf("B")));  // input changes while A is pending

        // A's stale response (10) must be discarded; the effect re-fires for B and folds 20 back.
        JsonNode sla = awaitValue(id, "$.ticket.slaMinutes", 20.0);
        assertThat(sla.asDouble()).isEqualTo(20.0);
        assertThat(service.getFieldValue(id, "$.ticket.category").asText()).isEqualTo("B");
    }

    @Test
    void timer_cancels_when_its_precondition_no_longer_holds() throws Exception {
        String id = "cancel-" + System.nanoTime();
        ModelSpec spec = mapper.readValue("""
                {
                  "id": "%s", "schema": {},
                  "defaultValues": [ { "path": "$", "expr": "{ 'status': 'open' }" } ],
                  "effects": [
                    { "id": "escalate", "executor": "timer",
                      "trigger": "status = 'open'", "dedupeKey": "status",
                      "afterMs": "300",
                      "response": { "set": { "$.status": "'escalated'" } },
                      "statusPath": "$.ioEscalate" }
                  ]
                }
                """.formatted(id), ModelSpec.class);
        service.createModel(spec);   // status='open' → timer armed for +300ms

        Thread.sleep(80);
        service.mutate(id, Map.of("$.status", TextNode.valueOf("closed")));  // handled before it escalates
        Thread.sleep(500);                                                   // past the fire time

        // The timer fired but its precondition (status='open') no longer holds → cancelled, not applied.
        assertThat(service.getFieldValue(id, "$.status").asText()).isEqualTo("closed");
        assertThat(service.getFieldValue(id, "$.ioEscalate.phase").asText()).isEqualTo("cancelled");
    }

    private JsonNode awaitValue(String id, String path, double expected) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            JsonNode v = service.getFieldValue(id, path);
            if (v != null && v.isNumber() && v.asDouble() == expected) return v;
            Thread.sleep(50);
        }
        throw new AssertionError("field " + path + " never became " + expected + " for model " + id
                + " (last=" + service.getFieldValue(id, path) + ")");
    }
}
