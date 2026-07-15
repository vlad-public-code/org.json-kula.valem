package org.json_kula.valem.api.authz;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
import org.json_kula.valem.api.reference.TemplateMaterializer;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 — inherited-effect approval (multi-tenant-authorization §4.2): branching a <em>different owner's</em>
 * template quarantines its inherited I/O effect until the brancher approves it; a same-owner branch runs
 * unprompted; approving the effect lets it fire.
 */
class InheritedEffectApprovalTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ModelService service;
    private TemplateMaterializer materializer;
    private EffectApprovalRegistry approvals;

    private ModelService wire(EffectApprovalRegistry.Mode mode) {
        service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        EffectMetrics metrics = new EffectMetrics(new SimpleMeterRegistry());
        ModelResolver resolver = new ModelResolver(List.of(new LocalModelRepository(service)));
        materializer = new TemplateMaterializer(resolver);
        approvals = new EffectApprovalRegistry(mode, service);
        HttpEffectExecutor http = new HttpEffectExecutor(service, new EgressGuard(false, 1_048_576), metrics);
        LinkEffectExecutor link = new LinkEffectExecutor(service, resolver, metrics);
        LlmEffectExecutor llm = new LlmEffectExecutor(service, null, metrics);
        TimerEffectExecutor timer = new TimerEffectExecutor(service, metrics);
        service.setEffectExecutor(new CompositeEffectExecutor(http, link, llm, timer, approvals, service));
        return service;
    }

    private ModelSpec spec(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    /** A template owned by "acme" carrying a link effect that writes to a sink model. */
    private static final String ACME_TEMPLATE = """
        {
          "id": "acme/base", "version": "1.0.0", "schema": {},
          "effects": [ {
            "id": "exfil", "executor": "server",
            "trigger": "n >= 0", "dedupeKey": "n",
            "target": { "ref": "sink", "path": "$.got" }, "body": "n",
            "statusPath": "$.io.exfil"
          } ]
        }
        """;

    private void createSink() throws Exception {
        service.createModel(spec("{\"id\":\"sink\",\"version\":\"1.0.0\",\"schema\":{}}"));
    }

    @Test
    void crossOwnerInheritedEffectIsQuarantinedThenApproved() throws Exception {
        wire(EffectApprovalRegistry.Mode.APPROVE);
        createSink();
        service.createModel(spec(ACME_TEMPLATE));

        // globex branches acme's template — the inherited "exfil" effect crosses an ownership boundary.
        ModelSpec branch = materializer.materialize(spec(
                "{\"id\":\"globex/quote\",\"version\":\"1.0.0\",\"schema\":{},\"template\":{\"ref\":\"acme/base\"}}"));
        service.createModel(branch);

        assertThat(approvals.pending("globex/quote")).singleElement()
                .satisfies(p -> {
                    assertThat(p.effectId()).isEqualTo("exfil");
                    assertThat(p.fromOwner()).isEqualTo("acme");
                });

        // Trigger it: the effect must NOT fire (sink untouched), statusPath shows the quarantine.
        service.mutate("globex/quote", Map.of("$.n", IntNode.valueOf(7)));
        await(() -> "effect_approval_required".equals(textAt("globex/quote", "$.io.exfil.error")));
        Thread.sleep(200);
        assertThat(intAt("sink", "$.got")).isEqualTo(Integer.MIN_VALUE);   // never written

        // Approve, then re-trigger — now it fires and reaches the sink.
        approvals.approve("globex/quote", "exfil");
        assertThat(approvals.pending("globex/quote")).isEmpty();
        service.mutate("globex/quote", Map.of("$.n", IntNode.valueOf(8)));
        await(() -> intAt("sink", "$.got") == 8);
    }

    @Test
    void sameOwnerBranchRunsWithoutApproval() throws Exception {
        wire(EffectApprovalRegistry.Mode.APPROVE);
        createSink();
        service.createModel(spec(ACME_TEMPLATE));

        // acme branches its own template — no ownership boundary crossed, no quarantine.
        ModelSpec branch = materializer.materialize(spec(
                "{\"id\":\"acme/quote\",\"version\":\"1.0.0\",\"schema\":{},\"template\":{\"ref\":\"acme/base\"}}"));
        service.createModel(branch);

        assertThat(approvals.pending("acme/quote")).isEmpty();
        service.mutate("acme/quote", Map.of("$.n", IntNode.valueOf(3)));
        await(() -> intAt("sink", "$.got") == 3);   // fires immediately
    }

    @Test
    void allowModeRunsInheritedEffectsWithoutApproval() throws Exception {
        wire(EffectApprovalRegistry.Mode.ALLOW);
        createSink();
        service.createModel(spec(ACME_TEMPLATE));
        ModelSpec branch = materializer.materialize(spec(
                "{\"id\":\"globex/quote\",\"version\":\"1.0.0\",\"schema\":{},\"template\":{\"ref\":\"acme/base\"}}"));
        service.createModel(branch);

        assertThat(approvals.pending("globex/quote")).isEmpty();
        service.mutate("globex/quote", Map.of("$.n", IntNode.valueOf(9)));
        await(() -> intAt("sink", "$.got") == 9);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private int intAt(String modelId, String path) {
        var v = service.getFieldValue(modelId, path);
        return v == null || v.isNull() || v.isMissingNode() ? Integer.MIN_VALUE : v.asInt();
    }

    private String textAt(String modelId, String path) {
        var v = service.getFieldValue(modelId, path);
        return v == null || v.isNull() ? null : v.asText();
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
