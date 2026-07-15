package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.json_kula.valem.api.authz.EffectApprovalRegistry;
import org.json_kula.valem.api.effects.CompositeEffectExecutor;
import org.json_kula.valem.api.effects.EffectMetrics;
import org.json_kula.valem.api.effects.EgressGuard;
import org.json_kula.valem.api.effects.HttpEffectExecutor;
import org.json_kula.valem.api.effects.LinkEffectExecutor;
import org.json_kula.valem.api.effects.LlmEffectExecutor;
import org.json_kula.valem.api.effects.TimerEffectExecutor;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 — a {@code watch: true} read-link keeps the source live w.r.t. the target: a change to the target's
 * watched path folds a fresh value into the source without the source being re-triggered. Teardown stops
 * the subscription.
 */
class WatchTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ModelService service;
    private WatchManager watch;

    @BeforeEach
    void wire() {
        service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        EffectMetrics metrics = new EffectMetrics(new SimpleMeterRegistry());
        ModelResolver resolver = new ModelResolver(List.of(new LocalModelRepository(service)));
        watch = new WatchManager(service);
        HttpEffectExecutor http = new HttpEffectExecutor(service, new EgressGuard(false, 1_048_576), metrics);
        LinkEffectExecutor link = new LinkEffectExecutor(service, resolver, metrics, watch);
        LlmEffectExecutor llm = new LlmEffectExecutor(service, null, metrics);
        TimerEffectExecutor timer = new TimerEffectExecutor(service, metrics);
        var approvals = new EffectApprovalRegistry(EffectApprovalRegistry.Mode.APPROVE, service);
        service.setEffectExecutor(new CompositeEffectExecutor(http, link, llm, timer, approvals, service));
    }

    private ModelSpec spec(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    @Test
    void watchTracksTargetLiveAndTearsDown() throws Exception {
        service.createModel(spec("{\"id\":\"rates\",\"version\":\"1.0.0\",\"schema\":{}}"));
        service.mutate("rates", Map.of("$.rate", mapper.getNodeFactory().numberNode(1.0)));

        service.createModel(spec("""
            { "id": "invoice", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "pull", "executor": "server",
                "trigger": "need = true", "dedupeKey": "need",
                "target": { "ref": "rates", "read": "$.rate", "watch": true },
                "response": { "set": { "$.fx": "$response" } },
                "statusPath": "$.io.fx"
              } ] }
            """));

        // Initial (snapshot) fold on trigger, and the watch is established.
        service.mutate("invoice", Map.of("$.need", BooleanNode.TRUE));
        await(() -> dbl("invoice", "$.fx") == 1.0);

        // A change to the target now pushes into the source — without re-triggering it.
        service.mutate("rates", Map.of("$.rate", mapper.getNodeFactory().numberNode(1.5)));
        await(() -> dbl("invoice", "$.fx") == 1.5);
        service.mutate("rates", Map.of("$.rate", mapper.getNodeFactory().numberNode(1.7)));
        await(() -> dbl("invoice", "$.fx") == 1.7);

        // Teardown: after removing the watch, further target changes do not reach the source.
        watch.teardownForModel("invoice");
        service.mutate("rates", Map.of("$.rate", mapper.getNodeFactory().numberNode(9.9)));
        Thread.sleep(300);
        assertThat(dbl("invoice", "$.fx")).isEqualTo(1.7);   // frozen at the last watched value
    }

    private double dbl(String id, String path) {
        JsonNode v = service.getFieldValue(id, path);
        return v == null || v.isNull() || v.isMissingNode() ? Double.NaN : v.asDouble();
    }

    private static void await(BooleanSupplier cond) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("condition not met within timeout");
    }
}
