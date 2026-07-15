package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSpecValidatorEffectsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelSpecValidator.ValidationResult validate(String json) throws Exception {
        return ModelSpecValidator.validate(MAPPER.readValue(json, ModelSpec.class));
    }

    private static boolean hasError(ModelSpecValidator.ValidationResult r, String needle) {
        return r.errors().stream().anyMatch(e ->
                (e.location() + " " + e.message()).toLowerCase().contains(needle.toLowerCase()));
    }

    @Test
    void valid_server_effect_passes() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null", "dedupeKey": "order.zip",
                      "request": { "method": "GET", "url": "/rate?zip={ order.zip }" },
                      "response": { "set": { "$.order.taxRate": "$response.rate" } },
                      "statusPath": "$.order.taxStatus",
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """);
        assertThat(r.isValid()).isTrue();
    }

    @Test
    void server_effect_requires_url() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null",
                      "request": { "method": "GET" } }
                  ]
                }
                """);
        assertThat(r.isValid()).isFalse();
        assertThat(hasError(r, "request.url")).isTrue();
    }

    @Test
    void server_effect_without_egress_profile_is_valid_in_sandbox() throws Exception {
        // URL is spec-provided; egressProfile is optional (production lockdown only).
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null",
                      "request": { "url": "https://tax.example.com/rate?zip={ order.zip }" },
                      "response": { "set": { "$.order.taxRate": "$response.rate" } } }
                  ]
                }
                """);
        assertThat(r.isValid()).isTrue();
    }

    @Test
    void server_effect_rejects_unknown_method() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null",
                      "request": { "method": "FETCH", "url": "/rate" },
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """);
        assertThat(hasError(r, "unsupported HTTP method")).isTrue();
    }

    @Test
    void server_effect_rejects_non_canonical_response_target() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null",
                      "request": { "url": "/rate" },
                      "response": { "set": { "order.taxRate": "$response.rate" } },
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """);
        assertThat(r.isValid()).isFalse();
        assertThat(hasError(r, "response.set")).isTrue();
    }

    @Test
    void valid_llm_effect_passes() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "classify", "executor": "llm",
                      "trigger": "ticket.body != null",
                      "prompt": "'Classify: ' & ticket.body",
                      "response": { "set": { "$.ticket.category": "$response.category" } } }
                  ]
                }
                """);
        assertThat(r.isValid()).isTrue();
    }

    @Test
    void llm_effect_requires_prompt() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "classify", "executor": "llm", "trigger": "ticket.body != null" }
                  ]
                }
                """);
        assertThat(r.isValid()).isFalse();
        assertThat(hasError(r, "prompt")).isTrue();
    }

    @Test
    void valid_timer_effect_passes() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "expire", "executor": "timer",
                      "trigger": "quote.status = 'sent'", "afterMs": "quote.ttlMs",
                      "response": { "set": { "$.quote.status": "'expired'" } } }
                  ]
                }
                """);
        assertThat(r.isValid()).isTrue();
    }

    @Test
    void timer_effect_requires_at_or_afterMs() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "expire", "executor": "timer", "trigger": "quote.status = 'sent'" }
                  ]
                }
                """);
        assertThat(r.isValid()).isFalse();
        assertThat(hasError(r, "afterMs")).isTrue();
    }

    @Test
    void caller_effect_requires_emit() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "toast", "executor": "caller",
                      "trigger": "order.done = true" }
                  ]
                }
                """);
        assertThat(r.isValid()).isFalse();
        assertThat(hasError(r, "emit")).isTrue();
    }

    @Test
    void duplicate_effect_ids_are_rejected() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "dup", "executor": "caller", "trigger": "a = 1", "emit": "e1" },
                    { "id": "dup", "executor": "caller", "trigger": "b = 2", "emit": "e2" }
                  ]
                }
                """);
        assertThat(hasError(r, "Duplicate effect id")).isTrue();
    }

    @Test
    void blank_trigger_is_rejected() throws Exception {
        var r = validate("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "toast", "executor": "caller", "trigger": "  ", "emit": "e" }
                  ]
                }
                """);
        assertThat(hasError(r, "trigger is required")).isTrue();
    }
}
