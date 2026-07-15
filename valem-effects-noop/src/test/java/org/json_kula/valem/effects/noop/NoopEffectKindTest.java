package org.json_kula.valem.effects.noop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.engine.spi.EffectKind;
import org.json_kula.valem.core.engine.spi.EffectKindRegistry;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the pluggable-effects SPI end to end on the pure-core side using nothing but this plugin jar +
 * {@code valem-core} — no core edits, no Spring. The {@code noop} kind is discovered, validated,
 * and dispatched into an {@link EffectRequest.Plugin} entirely through the public SPI.
 */
class NoopEffectKindTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPEC = """
            {
              "id": "m", "schema": {},
              "effects": [
                { "id": "echo", "executor": "noop",
                  "trigger": "greeting.name != null", "dedupeKey": "greeting.name",
                  "payload": { "message": "'Hello ' & greeting.name" },
                  "response": { "set": { "$.greeting.reply": "$response.message" } },
                  "statusPath": "$.greeting.io" }
              ]
            }
            """;

    @BeforeEach
    void enableAll() {
        EffectKindRegistry.configure(List.of());   // unrestricted: every discovered kind enabled
    }

    @AfterEach
    void reset() {
        EffectKindRegistry.configure(List.of());
    }

    @Test
    void serviceLoader_discovers_the_noop_kind() {
        boolean found = false;
        for (EffectKind k : ServiceLoader.load(EffectKind.class)) {
            if (NoopEffectKind.KIND.equals(k.kind())) found = true;
        }
        assertThat(found).as("NoopEffectKind is registered via META-INF/services").isTrue();

        EffectKindRegistry registry = EffectKindRegistry.get();
        assertThat(registry.isEnabled("noop")).isTrue();
        assertThat(registry.plugin("noop")).isInstanceOf(NoopEffectKind.class);
        assertThat(registry.isBuiltin("noop")).isFalse();
    }

    @Test
    void validator_accepts_a_noop_effect() throws Exception {
        ModelSpec spec = MAPPER.readValue(SPEC, ModelSpec.class);
        ModelSpecValidator.ValidationResult result = ModelSpecValidator.validate(spec);
        assertThat(result.isValid()).as("errors: %s", result.errors()).isTrue();
    }

    @Test
    void dispatcher_resolves_noop_to_a_plugin_request() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime(SPEC);
        rt.setEffectSink(received::add);

        rt.mutate("$.greeting.name", TextNode.valueOf("World"));

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).isInstanceOf(EffectRequest.Plugin.class);
        EffectRequest.Plugin p = (EffectRequest.Plugin) received.getFirst();
        assertThat(p.kind()).isEqualTo("noop");
        assertThat(p.effectId()).isEqualTo("echo");
        assertThat(p.statusPath()).isEqualTo("$.greeting.io");
        assertThat(p.dedupeKey()).isEqualTo(TextNode.valueOf("World"));
        assertThat(p.responseSet()).containsEntry("$.greeting.reply", "$response.message");
        assertThat(p.params().get("message").asText()).isEqualTo("Hello World");
    }

    @Test
    void validator_rejects_the_kind_when_the_enable_list_excludes_it() throws Exception {
        EffectKindRegistry.configure(List.of("server", "caller"));   // "noop" not enabled
        ModelSpec spec = MAPPER.readValue(SPEC, ModelSpec.class);
        ModelSpecValidator.ValidationResult result = ModelSpecValidator.validate(spec);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("noop"));
    }

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
