package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EffectDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void server_effect_emits_request_with_interpolated_url() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null", "dedupeKey": "order.zip",
                      "request": { "method": "GET", "url": "/rate?zip={ order.zip }" },
                      "response": { "set": { "$.order.taxRate": "$response.rate" } },
                      "statusPath": "$.order.taxStatus",
                      "policy": { "egressProfile": "tax-svc", "timeoutMs": 2500 } }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        rt.mutate("$.order.zip", TextNode.valueOf("12345"));

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).isInstanceOf(EffectRequest.Server.class);
        EffectRequest.Server s = (EffectRequest.Server) received.getFirst();
        assertThat(s.effectId()).isEqualTo("tax");
        assertThat(s.method()).isEqualTo("GET");
        assertThat(s.url()).isEqualTo("/rate?zip=12345");
        assertThat(s.egressProfile()).isEqualTo("tax-svc");
        assertThat(s.timeoutMs()).isEqualTo(2500);
        assertThat(s.responseSet()).containsEntry("$.order.taxRate", "$response.rate");
        assertThat(s.dedupeKey()).isEqualTo(TextNode.valueOf("12345"));
    }

    @Test
    void effect_does_not_fire_when_trigger_false() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null",
                      "request": { "url": "/rate" },
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        // Mutate a different field; the effect's trigger inputs never change.
        rt.mutate("$.order.note", TextNode.valueOf("hi"));

        assertThat(received).isEmpty();
    }

    @Test
    void caller_effect_emits_command_with_payload() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "toast", "executor": "caller",
                      "trigger": "order.done = true",
                      "emit": "order.confirmed",
                      "payload": { "msg": "'done'", "n": "order.count" } }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        rt.mutate(Map.of(
                "$.order.done",  BooleanNode.TRUE,
                "$.order.count", JsonNodeFactory.instance.numberNode(3)));

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).isInstanceOf(EffectRequest.Caller.class);
        EffectRequest.Caller c = (EffectRequest.Caller) received.getFirst();
        assertThat(c.emit()).isEqualTo("order.confirmed");
        assertThat(c.payload().get("msg").asText()).isEqualTo("done");
        assertThat(c.payload().get("n").asInt()).isEqualTo(3);
    }

    @Test
    void in_flight_guard_suppresses_refire() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null", "dedupeKey": "order.zip",
                      "request": { "url": "/rate?zip={ order.zip }" },
                      "statusPath": "$.order.taxStatus",
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        // Mark the effect in-flight, then trigger it — the guard must suppress the emission.
        rt.mutate("$.order.taxStatus.phase", TextNode.valueOf("in_flight"));
        rt.mutate("$.order.zip", TextNode.valueOf("12345"));

        assertThat(received).isEmpty();
    }

    @Test
    void dedupe_guard_suppresses_same_key() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null", "dedupeKey": "order.zip",
                      "request": { "url": "/rate?zip={ order.zip }" },
                      "statusPath": "$.order.taxStatus",
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        // The shell has already recorded that this edge value fired (phase applied, key 12345).
        rt.mutate(Map.of(
                "$.order.taxStatus.phase", TextNode.valueOf("applied"),
                "$.order.taxStatus.key",   TextNode.valueOf("12345")));
        rt.mutate("$.order.zip", TextNode.valueOf("12345"));

        assertThat(received).isEmpty();
    }

    @Test
    void fan_out_emits_one_request_per_array_element() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "enrich", "executor": "server",
                      "trigger": "order.items != null",
                      "requests": "order.items.{ 'method':'GET', 'url':'https://c.example.com/sku?id=' & sku, 'dedupeKey': sku }" }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        com.fasterxml.jackson.databind.node.ArrayNode items = JsonNodeFactory.instance.arrayNode();
        items.add(JsonNodeFactory.instance.objectNode().put("sku", "A"));
        items.add(JsonNodeFactory.instance.objectNode().put("sku", "B"));
        rt.mutate("$.order.items", items);

        assertThat(received).hasSize(2);
        assertThat(received).allMatch(r -> r instanceof EffectRequest.Server);
        assertThat(received).extracting(r -> ((EffectRequest.Server) r).url())
                .containsExactlyInAnyOrder(
                        "https://c.example.com/sku?id=A",
                        "https://c.example.com/sku?id=B");
        assertThat(received).extracting(r -> r.dedupeKey().asText())
                .containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void reconcile_redrives_stuck_in_flight_effect() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null", "dedupeKey": "order.zip",
                      "request": { "url": "/rate?zip={ order.zip }" },
                      "statusPath": "$.order.taxStatus",
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        rt.mutate("$.order.zip", TextNode.valueOf("12345"));   // fires normally
        received.clear();
        // Executor marked it in-flight, then the process "crashed" before folding back.
        rt.mutate("$.order.taxStatus.phase", TextNode.valueOf("in_flight"));
        assertThat(received).isEmpty();   // the phase write itself does not re-fire

        int redriven = rt.reconcileEffects();
        assertThat(redriven).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(((EffectRequest.Server) received.getFirst()).url()).isEqualTo("/rate?zip=12345");
    }

    @Test
    void reconcile_ignores_applied_effect() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null",
                      "request": { "url": "/rate" },
                      "statusPath": "$.order.taxStatus",
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        rt.mutate(Map.of(
                "$.order.zip", TextNode.valueOf("1"),
                "$.order.taxStatus.phase", TextNode.valueOf("applied")));
        received.clear();

        assertThat(rt.reconcileEffects()).isEqualTo(0);
        assertThat(received).isEmpty();
    }

    @Test
    void llm_effect_emits_request_with_resolved_prompt() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "classify", "executor": "llm",
                      "trigger": "ticket.body != null", "dedupeKey": "ticket.body",
                      "prompt": "'Classify: ' & ticket.body",
                      "response": { "set": { "$.ticket.category": "$response.category" } },
                      "statusPath": "$.ticket.io",
                      "policy": { "model": "m1", "temperature": 0 } }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        rt.mutate("$.ticket.body", TextNode.valueOf("help me"));

        assertThat(received).hasSize(1);
        EffectRequest.Llm l = (EffectRequest.Llm) received.getFirst();
        assertThat(l.prompt()).isEqualTo("Classify: help me");
        assertThat(l.model()).isEqualTo("m1");
        assertThat(l.temperature()).isEqualTo(0.0);
        assertThat(l.responseSet()).containsEntry("$.ticket.category", "$response.category");
    }

    @Test
    void timer_effect_afterMs_emits_delay() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "expire", "executor": "timer",
                      "trigger": "job.armed = true", "afterMs": "1000",
                      "response": { "set": { "$.job.done": "true" } },
                      "statusPath": "$.job.io" }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        rt.mutate("$.job.armed", BooleanNode.TRUE);

        assertThat(received).hasSize(1);
        EffectRequest.Timer t = (EffectRequest.Timer) received.getFirst();
        assertThat(t.delayMillis()).isEqualTo(1000L);
        assertThat(t.fireAtEpochMillis()).isNull();
    }

    @Test
    void timer_effect_at_emits_absolute_fire_time() throws Exception {
        List<EffectRequest> received = new ArrayList<>();
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "fire-at", "executor": "timer",
                      "trigger": "job.armed = true", "at": "job.fireAt",
                      "response": { "set": { "$.job.done": "true" } },
                      "statusPath": "$.job.io" }
                  ]
                }
                """);
        rt.setEffectSink(received::add);

        rt.mutate(Map.of(
                "$.job.armed",  BooleanNode.TRUE,
                "$.job.fireAt", JsonNodeFactory.instance.numberNode(1700000000000L)));

        assertThat(received).hasSize(1);
        EffectRequest.Timer t = (EffectRequest.Timer) received.getFirst();
        assertThat(t.fireAtEpochMillis()).isEqualTo(1700000000000L);
        assertThat(t.delayMillis()).isNull();
    }

    @Test
    void effect_included_in_mutation_result() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m", "schema": {},
                  "effects": [
                    { "id": "tax", "executor": "server",
                      "trigger": "order.zip != null",
                      "request": { "url": "/rate" },
                      "policy": { "egressProfile": "tax-svc" } }
                  ]
                }
                """);

        var result = rt.mutate("$.order.zip", TextNode.valueOf("99"));

        assertThat(result.hasEffects()).isTrue();
        assertThat(result.dispatchedEffects().getFirst().effectId()).isEqualTo("tax");
    }

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
