package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the {@code llm} and {@code timer} effect kinds through the real Spring shell.
 * A stub {@link LlmClient} bean returns canned JSON (no network / API key needed).
 */
@SpringBootTest
class LlmTimerEffectIT {

    @TestConfiguration
    static class StubLlmConfig {
        /** Returns canned JSON regardless of the prompt — enough to exercise the fold-back path. */
        @Bean
        LlmClient llmClient() {
            return prompt -> "{ \"category\": \"billing\", \"urgency\": 3 }";
        }
    }

    @Autowired ModelService service;
    @Autowired ObjectMapper mapper;

    @Test
    void llm_effect_folds_completion_back() throws Exception {
        String id = "llm-it-" + System.nanoTime();
        ModelSpec spec = mapper.readValue("""
                {
                  "id": "%s", "schema": {},
                  "effects": [
                    { "id": "classify", "executor": "llm",
                      "trigger": "ticket.body != null", "dedupeKey": "ticket.body",
                      "prompt": "'Classify this ticket: ' & ticket.body",
                      "response": { "set": {
                        "$.ticket.category": "$response.category",
                        "$.ticket.urgency":  "$response.urgency" } },
                      "statusPath": "$.ticket.ioClassify" }
                  ]
                }
                """.formatted(id), ModelSpec.class);

        service.createModel(spec);
        service.mutate(id, Map.of("$.ticket.body", TextNode.valueOf("I was double charged")));

        JsonNode category = await(id, "$.ticket.category");
        assertThat(category.asText()).isEqualTo("billing");
        assertThat(service.getFieldValue(id, "$.ticket.urgency").asInt()).isEqualTo(3);
        assertThat(service.getFieldValue(id, "$.ticket.ioClassify.phase").asText()).isEqualTo("applied");
    }

    @Test
    void timer_effect_fires_after_delay_and_folds_back() throws Exception {
        String id = "timer-it-" + System.nanoTime();
        ModelSpec spec = mapper.readValue("""
                {
                  "id": "%s", "schema": {},
                  "effects": [
                    { "id": "expire", "executor": "timer",
                      "trigger": "quote.status = 'sent'", "dedupeKey": "quote.id",
                      "afterMs": "200",
                      "response": { "set": { "$.quote.status": "'expired'" } },
                      "statusPath": "$.quote.ioExpiry" }
                  ]
                }
                """.formatted(id), ModelSpec.class);

        service.createModel(spec);
        service.mutate(id, Map.of(
                "$.quote.id",     TextNode.valueOf("q1"),
                "$.quote.status", TextNode.valueOf("sent")));

        // Immediately after arming, the timer is scheduled (in_flight) but has not fired.
        assertThat(service.getFieldValue(id, "$.quote.status").asText()).isEqualTo("sent");

        // After the delay the fold-back lands: status flips to 'expired'.
        JsonNode status = awaitValue(id, "$.quote.status", "expired");
        assertThat(status.asText()).isEqualTo("expired");
        assertThat(service.getFieldValue(id, "$.quote.ioExpiry.phase").asText()).isEqualTo("applied");
    }

    @Test
    void creation_time_effect_resolves_against_the_registered_model() throws Exception {
        // Regression: a $-seeded default fires a timer during createModel's initialize(). The model
        // must already be registered so the async fold-back resolves (no ModelNotFound, effect applies).
        String id = "creation-effect-" + System.nanoTime();
        ModelSpec spec = mapper.readValue("""
                {
                  "id": "%s", "schema": {},
                  "defaultValues": [ { "path": "$", "expr": "{ 'status': 'sent' }" } ],
                  "effects": [
                    { "id": "expire", "executor": "timer",
                      "trigger": "status = 'sent'", "dedupeKey": "status",
                      "afterMs": "150",
                      "response": { "set": { "$.status": "'expired'" } },
                      "statusPath": "$.ioExpiry" }
                  ]
                }
                """.formatted(id), ModelSpec.class);

        service.createModel(spec);   // seeds status='sent' at creation, which arms the timer

        JsonNode status = awaitValue(id, "$.status", "expired");
        assertThat(status.asText()).isEqualTo("expired");
        assertThat(service.getFieldValue(id, "$.ioExpiry.phase").asText()).isEqualTo("applied");
    }

    private JsonNode await(String id, String path) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            JsonNode v = service.getFieldValue(id, path);
            if (v != null && !v.isNull() && !v.isMissingNode()) return v;
            Thread.sleep(50);
        }
        throw new AssertionError("field " + path + " never appeared for model " + id);
    }

    private JsonNode awaitValue(String id, String path, String expected) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            JsonNode v = service.getFieldValue(id, path);
            if (v != null && v.isTextual() && v.asText().equals(expected)) return v;
            Thread.sleep(50);
        }
        throw new AssertionError("field " + path + " never became '" + expected + "' for model " + id);
    }
}
