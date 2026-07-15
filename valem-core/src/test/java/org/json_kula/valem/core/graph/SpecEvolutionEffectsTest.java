package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpecEvolutionEffectsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void evolution_carries_effects_forward() throws Exception {
        ModelSpec base = MAPPER.readValue("""
                {
                  "id": "m", "version": "1.0.0", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null",
                      "request": { "url": "/rate" },
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """, ModelSpec.class);

        SpecEvolution evo = MAPPER.readValue("{ \"newVersion\": \"1.1.0\" }", SpecEvolution.class);
        ModelSpec evolved = evo.applyTo(base);

        assertThat(evolved.version()).isEqualTo("1.1.0");
        assertThat(evolved.effects()).hasSize(1);
        assertThat(evolved.effects().getFirst().id()).isEqualTo("tax");
    }

    @Test
    void evolution_upserts_and_removes_effects() throws Exception {
        ModelSpec base = MAPPER.readValue("""
                {
                  "id": "m", "version": "1.0.0", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server", "trigger": "order.zip != null",
                      "request": { "url": "https://a.example.com/rate" } },
                    { "id": "legacy", "executor": "caller", "trigger": "x = 1", "emit": "e" }
                  ]
                }
                """, ModelSpec.class);

        // Remove 'legacy', update 'tax' (new url), add 'ship'.
        SpecEvolution evo = MAPPER.readValue("""
                {
                  "removeEffects": ["legacy"],
                  "upsertEffects": [
                    { "id": "tax", "executor": "server", "trigger": "order.zip != null",
                      "request": { "url": "https://b.example.com/rate" } },
                    { "id": "ship", "executor": "server", "trigger": "order.weight != null",
                      "request": { "url": "https://ship.example.com/quote" } }
                  ]
                }
                """, SpecEvolution.class);
        ModelSpec evolved = evo.applyTo(base);

        assertThat(evolved.effects()).extracting(e -> e.id())
                .containsExactlyInAnyOrder("tax", "ship");
        EffectSpec tax = evolved.effects().stream().filter(e -> e.id().equals("tax")).findFirst().orElseThrow();
        assertThat(tax.request().url()).isEqualTo("https://b.example.com/rate");
    }
}
