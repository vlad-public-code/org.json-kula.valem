package org.json_kula.valem.api.composition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.json_kula.valem.api.effects.CompositeEffectExecutor;
import org.json_kula.valem.api.effects.EffectMetrics;
import org.json_kula.valem.api.effects.EgressGuard;
import org.json_kula.valem.api.effects.HttpEffectExecutor;
import org.json_kula.valem.api.effects.LinkEffectExecutor;
import org.json_kula.valem.api.effects.LlmEffectExecutor;
import org.json_kula.valem.api.effects.TimerEffectExecutor;
import org.json_kula.valem.api.reference.LocalModelRepository;
import org.json_kula.valem.api.reference.ModelResolver;
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
 * M2 exit criterion — local composition end-to-end: two leaf models push their subtotals into one
 * aggregate via {@code local} write-links; the aggregate's wildcard derivation rolls them up; each
 * leaf folds the aggregate's returned total back. Exercises resolver → ModelLink → fold-through CAS
 * with no remote transport and no Spring.
 */
class LocalCompositionTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ModelService service;

    @BeforeEach
    void wire() {
        service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        EffectMetrics metrics = new EffectMetrics(new SimpleMeterRegistry());
        HttpEffectExecutor http = new HttpEffectExecutor(service, new EgressGuard(false, 1_048_576), metrics);
        ModelResolver resolver = new ModelResolver(List.of(new LocalModelRepository(service)));
        LinkEffectExecutor link = new LinkEffectExecutor(service, resolver, metrics);
        LlmEffectExecutor llm = new LlmEffectExecutor(service, null, metrics);
        TimerEffectExecutor timer = new TimerEffectExecutor(service, metrics);
        var approvals = new org.json_kula.valem.api.authz.EffectApprovalRegistry(
                org.json_kula.valem.api.authz.EffectApprovalRegistry.Mode.APPROVE, service);
        service.setEffectExecutor(new CompositeEffectExecutor(http, link, llm, timer, approvals, service));
    }

    private static final String AGG_SPEC = """
            {
              "id": "agg", "version": "1.0.0", "schema": {},
              "derivations": [ {"path": "$.total", "expr": "$sum(children.*.subtotal)"} ]
            }
            """;

    private static String leaf(String id, String key) {
        return """
            {
              "id": "%s", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "push", "executor": "server",
                "trigger": "subtotal >= 0", "dedupeKey": "subtotal",
                "target": { "ref": "agg", "path": "$.children.%s.subtotal" },
                "body": "subtotal",
                "response": { "set": { "$.io.ackTotal": "$response.total" } },
                "statusPath": "$.io.push"
              } ]
            }
            """.formatted(id, key);
    }

    private ModelSpec spec(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    @Test
    void twoLeavesFanInToAggregate() throws Exception {
        service.createModel(spec(AGG_SPEC));
        service.createModel(spec(leaf("leaf-a", "a")));
        service.createModel(spec(leaf("leaf-b", "b")));

        service.mutate("leaf-a", Map.of("$.subtotal", IntNode.valueOf(100)));
        await(() -> intAt("agg", "$.total") == 100);

        service.mutate("leaf-b", Map.of("$.subtotal", IntNode.valueOf(250)));
        await(() -> intAt("agg", "$.total") == 350);

        // Each leaf received the aggregate's returned total via the fold-back.
        await(() -> intAt("leaf-b", "$.io.ackTotal") == 350);
        assertThat(intAt("agg", "$.total")).isEqualTo(350);
        assertThat(textAt("leaf-a", "$.io.push.phase")).isEqualTo("applied");

        // The aggregate was mutated by the link (its children subtree exists); the read side is intact.
        assertThat(intAt("agg", "$.children.a.subtotal")).isEqualTo(100);
        assertThat(intAt("agg", "$.children.b.subtotal")).isEqualTo(250);
    }

    @Test
    void readLinkImportsValueWithoutMutatingTarget() throws Exception {
        // B holds an fx rate; A reads it by path (read-link) — B must not be mutated.
        service.createModel(spec("{\"id\":\"rates\",\"version\":\"1.0.0\",\"schema\":{}}"));
        service.mutate("rates", Map.of("$.usd", mapper.getNodeFactory().numberNode(1.1)));
        int ratesHistoryBefore = service.getHistory("rates").size();

        String invoice = """
            {
              "id": "invoice", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "pull", "executor": "server",
                "trigger": "need = true", "dedupeKey": "need",
                "target": { "ref": "rates", "read": "$.usd" },
                "response": { "set": { "$.fxRate": "$response" } },
                "statusPath": "$.io.fx"
              } ]
            }
            """;
        service.createModel(spec(invoice));
        service.mutate("invoice", Map.of("$.need", com.fasterxml.jackson.databind.node.BooleanNode.TRUE));

        await(() -> doubleAt("invoice", "$.fxRate") == 1.1);
        assertThat(textAt("invoice", "$.io.fx.phase")).isEqualTo("applied");
        // The read did not mutate B: no new history entry, value intact.
        assertThat(service.getHistory("rates")).hasSize(ratesHistoryBefore);
        assertThat(doubleAt("rates", "$.usd")).isEqualTo(1.1);
    }

    @Test
    void guardedPeerCycleSettlesWithoutDeadlock() throws Exception {
        // A ⇄ B: each writes to the other, every edge guarded by statusPath + dedupeKey so the
        // edge-trigger reaches a fixpoint. The property under test is progress + no deadlock.
        String ping = """
            {
              "id": "ping", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "toPong", "executor": "server",
                "trigger": "n >= 0", "dedupeKey": "n",
                "target": { "ref": "pong", "path": "$.fromPing" }, "body": "n",
                "response": { "set": { "$.echo": "$response.fromPing" } },
                "statusPath": "$.io.p"
              } ]
            }
            """;
        String pong = """
            {
              "id": "pong", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "toPing", "executor": "server",
                "trigger": "fromPing >= 0", "dedupeKey": "fromPing",
                "target": { "ref": "ping", "path": "$.fromPong" }, "body": "fromPing",
                "response": { "set": { "$.echo2": "$response.fromPong" } },
                "statusPath": "$.io.q"
              } ]
            }
            """;
        service.createModel(spec(ping));
        service.createModel(spec(pong));

        service.mutate("ping", Map.of("$.n", IntNode.valueOf(5)));

        // Both edges settle to "applied" (phase lags the value write, which happens in-flight).
        await(() -> "applied".equals(textAt("ping", "$.io.p.phase"))
                && "applied".equals(textAt("pong", "$.io.q.phase")));
        assertThat(intAt("pong", "$.fromPing")).isEqualTo(5);
        assertThat(intAt("ping", "$.fromPong")).isEqualTo(5);
        assertThat(intAt("ping", "$.echo")).isEqualTo(5);
        assertThat(intAt("pong", "$.echo2")).isEqualTo(5);
    }

    @Test
    void writeLinkRejectedByTargetConstraintSurfacesTargetRejected() throws Exception {
        // The aggregate rejects a subtotal over its cap with a rollback constraint.
        String cappedAgg = """
            {
              "id": "capped", "version": "1.0.0", "schema": {},
              "constraints": [ {"id": "cap", "expr": "$not($exists(inbound)) or inbound <= 100",
                                "policy": "rollback"} ]
            }
            """;
        service.createModel(spec(cappedAgg));
        String leaf = """
            {
              "id": "over", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "push", "executor": "server",
                "trigger": "subtotal >= 0", "dedupeKey": "subtotal",
                "target": { "ref": "capped", "path": "$.inbound" }, "body": "subtotal",
                "statusPath": "$.io.push"
              } ]
            }
            """;
        service.createModel(spec(leaf));

        service.mutate("over", Map.of("$.subtotal", IntNode.valueOf(500)));  // over the cap

        await(() -> "failed".equals(textAt("over", "$.io.push.phase")));
        assertThat(textAt("over", "$.io.push.error")).startsWith("target_rejected");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private double doubleAt(String modelId, String path) {
        JsonNode v = service.getFieldValue(modelId, path);
        return v == null || v.isNull() ? Double.NaN : v.asDouble();
    }

    private int intAt(String modelId, String path) {
        JsonNode v = service.getFieldValue(modelId, path);
        return v == null || v.isNull() ? Integer.MIN_VALUE : v.asInt();
    }

    private String textAt(String modelId, String path) {
        JsonNode v = service.getFieldValue(modelId, path);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Polls until the async link fold-backs settle (links fire post-commit on virtual threads). */
    private static void await(BooleanSupplier cond) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("condition not met within timeout");
    }
}
