package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** M7: a runtime built with a seed cache reuses already-compiled expressions. */
class ExpressionCacheCarryForwardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelRuntime runtime(String specJson, ExpressionCache seed) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        return new ModelRuntime(model, new ModelState(model, new InMemoryBlobStore()), seed);
    }

    @Test
    void seeded_runtime_reuses_compiled_expressions_and_still_compiles_new_ones() throws Exception {
        String spec = """
                {
                  "id": "m", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "subtotal + tax" } ]
                }
                """;
        ModelRuntime first = runtime(spec, null);
        first.recomputeAllDerivations();   // forces "subtotal + tax" to compile
        assertThat(first.expressionCache().isCompiled("subtotal + tax")).isTrue();

        // A fresh runtime seeded from the first already has the compiled expression…
        ModelRuntime second = runtime(spec, first.expressionCache());
        assertThat(second.expressionCache().isCompiled("subtotal + tax")).isTrue();
        // …but an expression it has never seen is not yet compiled.
        assertThat(second.expressionCache().isCompiled("subtotal * 2")).isFalse();
    }

    @Test
    void unseeded_runtime_starts_cold() throws Exception {
        ModelRuntime rt = runtime("""
                { "id": "m", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "subtotal + tax" } ] }
                """, null);
        assertThat(rt.expressionCache().isCompiled("subtotal + tax")).isFalse();
    }
}
