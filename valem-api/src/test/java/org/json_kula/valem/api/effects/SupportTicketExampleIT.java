package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sun.net.httpserver.HttpServer;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the shipped UI example {@code support-ticket-triage.json}, exercising ALL four
 * effect kinds against test stubs: {@code llm} (stub LlmClient bean), {@code server} (local stub server,
 * URL rewritten to it), {@code caller} (surfaced in the mutation response), and {@code timer} (delay
 * shortened to fire during the test). The example file is the single source of truth — the test loads
 * it and asserts it is a valid, working spec.
 */
@SpringBootTest(properties = {"valem.effects.allow-private-ips=true", "valem.effects.allow-insecure-http=true"})
class SupportTicketExampleIT {

    @TestConfiguration
    static class StubLlmConfig {
        @Bean
        LlmClient llmClient() {
            return prompt -> "{ \"category\": \"billing\", \"urgency\": 4 }";
        }
    }

    @Autowired ModelService service;
    @Autowired ObjectMapper mapper;

    private HttpServer stub;
    private int port;

    @BeforeEach
    void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/lookup", exchange -> {
            byte[] body = "{ \"responseMinutes\": 60 }".getBytes(StandardCharsets.UTF_8);
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
    void support_ticket_example_exercises_all_four_effect_kinds() throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(loadExample());
        // Strip the UI-only metadata fields (not part of ModelSpec).
        root.remove("_name");
        root.remove("_description");
        // Point the server effect at the local stub and shorten the timer so it fires during the test.
        for (JsonNode e : root.withArray("effects")) {
            String executor = e.path("executor").asText();
            if ("server".equals(executor)) {
                String url = e.path("request").path("url").asText()
                        .replace("https://sla.example.com", "http://127.0.0.1:" + port);
                ((ObjectNode) e.get("request")).put("url", url);
            } else if ("timer".equals(executor)) {
                ((ObjectNode) e).put("afterMs", "200");
            }
        }

        String id = "support-ticket-" + System.nanoTime();
        ModelSpec spec = withId(root, id);

        service.createModel(spec);   // seeds status='open' → arms the (200ms) escalation timer

        // 1. CALLER — mark the ticket urgent directly; the caller effect is surfaced in the response.
        var outcome = service.mutate(id, Map.of("$.ticket.urgency", mapper.getNodeFactory().numberNode(5)));
        List<EffectRequest> fired = outcome.result().dispatchedEffects();
        assertThat(fired).anyMatch(r -> r instanceof EffectRequest.Caller c && c.emit().equals("ticket.urgent"));

        // 2. LLM — set the description; the stub model fills category + urgency via fold-back.
        service.mutate(id, Map.of("$.ticket.description", TextNode.valueOf("I was charged twice for my order")));
        assertThat(await(id, "$.ticket.category").asText()).isEqualTo("billing");

        // 3. SERVER — with the category set, the SLA lookup folds the response time back.
        assertThat(await(id, "$.ticket.responseMinutes").asDouble()).isEqualTo(60.0);
        assertThat(service.getFieldValue(id, "$.ticket.ioSla.phase").asText()).isEqualTo("applied");

        // 4. TIMER — the open ticket auto-escalates after the (shortened) delay.
        assertThat(awaitValue(id, "$.ticket.status", "escalated").asText()).isEqualTo("escalated");
        assertThat(service.getFieldValue(id, "$.ticket.ioEscalate.phase").asText()).isEqualTo("applied");
    }

    private ModelSpec withId(ObjectNode root, String id) throws Exception {
        root.put("id", id);
        return mapper.treeToValue(root, ModelSpec.class);
    }

    private String loadExample() throws Exception {
        for (String candidate : new String[]{
                "../valem-ui/src/examples/support-ticket-triage.json",
                "valem-ui/src/examples/support-ticket-triage.json"}) {
            Path p = Path.of(candidate);
            if (Files.exists(p)) return Files.readString(p);
        }
        throw new AssertionError("could not locate support-ticket-triage.json (cwd=" + Path.of(".").toAbsolutePath() + ")");
    }

    private JsonNode await(String id, String path) throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            JsonNode v = service.getFieldValue(id, path);
            if (v != null && !v.isNull() && !v.isMissingNode()) return v;
            Thread.sleep(50);
        }
        throw new AssertionError("field " + path + " never appeared for model " + id);
    }

    private JsonNode awaitValue(String id, String path, String expected) throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            JsonNode v = service.getFieldValue(id, path);
            if (v != null && v.isTextual() && v.asText().equals(expected)) return v;
            Thread.sleep(50);
        }
        throw new AssertionError("field " + path + " never became '" + expected + "' for model " + id);
    }
}
