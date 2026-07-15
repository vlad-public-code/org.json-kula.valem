package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.EffectDispatcher.FoldbackDecision;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FoldbackResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPEC = """
            {
              "id": "m", "schema": {},
              "effects": [
                { "id": "tax", "executor": "server",
                  "trigger": "order.zip != null", "dedupeKey": "order.zip",
                  "request": { "url": "https://svc/r?zip={ order.zip }" },
                  "response": { "set": { "$.order.taxRate": "$response.rate" } },
                  "statusPath": "$.order.io" }
              ]
            }
            """;

    @Test
    void resolve_is_CURRENT_when_fired_key_still_matches() throws Exception {
        ModelRuntime rt = runtime();
        rt.mutate("$.order.zip", TextNode.valueOf("B"));
        assertThat(rt.resolveFoldback("tax", TextNode.valueOf("B"))).isEqualTo(FoldbackDecision.CURRENT);
    }

    @Test
    void resolve_is_SUPERSEDED_when_input_changed_while_in_flight() throws Exception {
        ModelRuntime rt = runtime();
        rt.mutate("$.order.zip", TextNode.valueOf("B"));      // current value is now B
        // The effect was fired for the older value A → its fold-back is stale.
        assertThat(rt.resolveFoldback("tax", TextNode.valueOf("A"))).isEqualTo(FoldbackDecision.SUPERSEDED);
    }

    @Test
    void resolve_is_CANCELLED_when_trigger_no_longer_holds() throws Exception {
        ModelRuntime rt = runtime();
        rt.mutate("$.order.zip", TextNode.valueOf("A"));
        rt.mutate("$.order.zip", NullNode.instance);         // zip != null is now false
        assertThat(rt.resolveFoldback("tax", TextNode.valueOf("A"))).isEqualTo(FoldbackDecision.CANCELLED);
    }

    @Test
    void redispatch_emits_a_fresh_request_for_the_current_value() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime();
        rt.setEffectSink(received::add);

        rt.mutate("$.order.zip", TextNode.valueOf("B"));      // fires once for B
        received.clear();

        rt.redispatchEffect("tax");                          // trailing re-fire for the current value

        assertThat(received).hasSize(1);
        EffectRequest.Server s = (EffectRequest.Server) received.getFirst();
        assertThat(s.url()).isEqualTo("https://svc/r?zip=B");
        assertThat(s.dedupeKey()).isEqualTo(TextNode.valueOf("B"));
    }

    private ModelRuntime runtime() throws Exception {
        ModelSpec spec = MAPPER.readValue(SPEC, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        return new ModelRuntime(model, new ModelState(model, new InMemoryBlobStore()));
    }
}
