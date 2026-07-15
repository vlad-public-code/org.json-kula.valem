package org.json_kula.valem.effects.noop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the pluggable-effects SPI: boots a <b>real</b> {@code valem-api} Spring
 * Boot context ({@link NoopE2EBootApplication}, scanning the {@code ...api} package) with nothing on
 * the classpath beyond this plugin module and its declared dependencies — exactly what a host
 * application looks like once someone drops
 * the {@code valem-effects-noop} jar in. No test wiring names {@link NoopEffectKind} or
 * {@link NoopEffectExecutor} directly: {@code EffectKindRegistry} discovers the kind via
 * {@code ServiceLoader}, and {@link NoopEffectAutoConfiguration} registers the executor bean purely from
 * the {@code META-INF/spring/...AutoConfiguration.imports} file this module ships. That is the entire
 * "add a kind" contract — a jar and (optionally) an enable-list entry, no core/api edits.
 *
 * <p>Drives the full reactive-engine + fold-back loop through {@link ModelService} exactly like the
 * built-in {@code HttpEffectExecutorIT}/{@code LlmTimerEffectIT} do for {@code server}/{@code llm}: a
 * mutation fires the {@code noop} effect, {@link NoopEffectExecutor} runs asynchronously post-commit,
 * and the result folds back via the same keyed compare-and-swap the built-in kinds use.
 */
@SpringBootTest(classes = NoopE2EBootApplication.class)
class NoopEffectE2ETest {

    @Autowired ModelService service;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void noop_effect_fires_and_folds_back_through_the_real_application_context() throws Exception {
        String id = "noop-e2e-" + System.nanoTime();
        ModelSpec spec = mapper.readValue("""
                {
                  "id": "%s", "schema": {},
                  "effects": [
                    { "id": "echo", "executor": "noop",
                      "trigger": "greeting.name != null", "dedupeKey": "greeting.name",
                      "payload": { "message": "'Hello ' & greeting.name" },
                      "response": { "set": { "$.greeting.reply": "$response.message" } },
                      "statusPath": "$.greeting.io" }
                  ]
                }
                """.formatted(id), ModelSpec.class);

        service.createModel(spec);
        service.mutate(id, Map.of("$.greeting.name", TextNode.valueOf("World")));

        JsonNode reply = await(id, "$.greeting.reply");
        assertThat(reply.asText()).isEqualTo("Hello World");
        assertThat(service.getFieldValue(id, "$.greeting.io.phase").asText()).isEqualTo("applied");

        // The plugin executor recorded through the same shared EffectMetrics as the built-in kinds.
        var timer = meterRegistry.find("valem.effect.duration")
                .tag("kind", "noop").tag("outcome", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
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
