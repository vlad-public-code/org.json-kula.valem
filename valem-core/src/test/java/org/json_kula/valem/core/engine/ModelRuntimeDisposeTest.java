package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2 — reclaiming classes on model disposal. {@link ModelRuntime#dispose()} must drop the per-instance
 * compiled-expression references of a runtime that <b>owns</b> its cache, but never touch a borrowed,
 * server-lifetime shared cache still used by other runtimes.
 */
class ModelRuntimeDisposeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SPEC = """
            { "id": "m", "schema": {},
              "derivations": [ { "path": "$.total", "expr": "subtotal + tax" } ] }
            """;

    private CompiledModel model() throws Exception {
        return ModelSpecCompiler.compile(MAPPER.readValue(SPEC, ModelSpec.class));
    }

    private ModelState freshState(CompiledModel m) {
        return new ModelState(m, new InMemoryBlobStore());
    }

    @Test
    void dispose_clears_an_owned_cache() throws Exception {
        CompiledModel m = model();
        ModelRuntime rt = new ModelRuntime(m, freshState(m));   // owns its cache
        rt.recomputeAllDerivations();                            // compiles "subtotal + tax"
        assertThat(rt.expressionCache().size()).isPositive();

        rt.dispose();
        assertThat(rt.expressionCache().size())
                .as("an owned cache's per-instance references are dropped on dispose")
                .isZero();
    }

    @Test
    void dispose_leaves_a_borrowed_shared_cache_intact() throws Exception {
        CompiledModel m = model();
        ExpressionCache shared = new ExpressionCache();
        shared.get("subtotal + tax");
        int before = shared.size();

        ModelRuntime rt = ModelRuntime.withSharedCache(m, freshState(m), shared);
        rt.recomputeAllDerivations();
        rt.dispose();

        assertThat(shared.size())
                .as("a borrowed server-lifetime cache is never cleared by one runtime's disposal")
                .isEqualTo(before);
        assertThat(shared.isCompiled("subtotal + tax")).isTrue();
    }

    @Test
    void dispose_is_idempotent() throws Exception {
        CompiledModel m = model();
        ModelRuntime rt = new ModelRuntime(m, freshState(m));
        rt.recomputeAllDerivations();
        rt.dispose();
        rt.dispose(); // must not throw
        assertThat(rt.expressionCache().size()).isZero();
    }
}
