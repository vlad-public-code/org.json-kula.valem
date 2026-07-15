package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the effect loop through the pure core, using a fake shell in place of the
 * Spring/HTTP executor: a mutation fires the effect, the fake shell "responds" and maps the response
 * via {@code response.set}, folds it back as an ordinary mutation, and a derivation recomputes on the
 * folded value. The fold-back is performed after {@code mutate()} returns (mirroring the real
 * post-commit, out-of-transaction execution — never re-entrantly).
 */
class EffectFoldbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void response_folds_back_and_a_derivation_recomputes() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "derivations": [
                    { "path": "$.order.tax", "expr": "order.subtotal * order.taxRate" }
                  ],
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

        List<EffectRequest> captured = new ArrayList<>();
        rt.setEffectSink(captured::add);

        // 1. Seed subtotal, then set the zip that fires the effect.
        rt.mutate("$.order.subtotal", JsonNodeFactory.instance.numberNode(100.0));
        rt.mutate("$.order.zip", TextNode.valueOf("12345"));

        // 2. The core emitted one server request; no I/O happened in the core.
        assertThat(captured).hasSize(1);
        EffectRequest.Server req = (EffectRequest.Server) captured.getFirst();
        assertThat(req.url()).isEqualTo("/rate?zip=12345");

        // 3. Fake shell: perform the "HTTP" call and map the response via response.set.
        JsonNode response = MAPPER.readTree("{ \"rate\": 0.08 }");
        Map<String, JsonNode> foldback = applyResponseSet(rt, req, response);
        // The shell also records the terminal status (phase + edge key) so replays / re-fires dedupe.
        foldback.put("$.order.taxStatus.phase", TextNode.valueOf("applied"));
        foldback.put("$.order.taxStatus.key", req.dedupeKey());

        rt.mutate(foldback);

        // 4. The folded taxRate cascaded through the derivation.
        assertThat(rt.getValue("$.order.taxRate").asDouble()).isEqualTo(0.08);
        assertThat(rt.getValue("$.order.tax").asDouble()).isEqualTo(8.0);

        // 5. Re-firing with the same edge value is deduped (guard on the recorded key).
        captured.clear();
        rt.mutate("$.order.zip", TextNode.valueOf("12345"));
        assertThat(captured).isEmpty();
    }

    /** Evaluates each {@code response.set} value expression with {@code $response} bound. */
    private Map<String, JsonNode> applyResponseSet(ModelRuntime rt, EffectRequest.Server req, JsonNode response)
            throws Exception {
        ExpressionCache cache = rt.expressionCache();
        JsonataBindings bindings = new JsonataBindings().bindValue("response", response);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : req.responseSet().entrySet()) {
            JsonNode v = cache.get(e.getValue()).evaluate(NullNode.instance, bindings);
            out.put(e.getKey(), v);
        }
        return out;
    }

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
