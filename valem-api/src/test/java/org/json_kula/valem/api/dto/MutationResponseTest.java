package org.json_kula.valem.api.dto;

import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MutationResponseTest {

    @Test
    void surfaces_caller_effects_and_excludes_server_effects() {
        EffectRequest.Caller caller = new EffectRequest.Caller(
                "toast", "order.confirmed", TextNode.valueOf("hi"), "$.order.io", null);
        EffectRequest.Server server = EffectRequest.Server.http(
                "tax", "GET", "https://x/r", Map.of(), null, Map.of(),
                null, 5000, 0, null, "$.order.io2", null);

        ModelRuntime.MutationResult result = new ModelRuntime.MutationResult(
                true, List.of(), List.of(), List.of(), List.of(),
                List.of(caller, server), List.of());

        MutationResponse resp = MutationResponse.from(result);

        assertThat(resp.dispatchedEffects()).hasSize(1);
        assertThat(resp.dispatchedEffects().getFirst().effectId()).isEqualTo("toast");
        assertThat(resp.dispatchedEffects().getFirst().emit()).isEqualTo("order.confirmed");
        assertThat(resp.dispatchedEffects().getFirst().payload().asText()).isEqualTo("hi");
    }
}
