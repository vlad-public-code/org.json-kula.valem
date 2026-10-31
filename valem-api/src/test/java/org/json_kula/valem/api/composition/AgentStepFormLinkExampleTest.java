package org.json_kula.valem.api.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundled agent-step-brief-form example drives the bundled agent-report-pipeline through a
 * local write-link: a workflow step that is its own model hands its result to the parent, and the
 * parent reacts as if the brief had been typed into it directly.
 */
class AgentStepFormLinkExampleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Test
    void submitting_the_form_starts_the_pipeline() throws Exception {
        service.createModel(example("agent-report-pipeline.json"));
        service.createModel(example("agent-step-brief-form.json"));
        assertThat(textAt("agent-report-pipeline", "$.status.next")).isEqualTo("write-brief");

        Map<String, JsonNode> fill = new LinkedHashMap<>();
        fill.put("$.form.topic", TextNode.valueOf("Heat pumps"));
        fill.put("$.form.audience", TextNode.valueOf("homeowners"));
        fill.put("$.form.requestedBy", TextNode.valueOf("Dana"));
        service.mutate("agent-step-brief-form", fill);
        // Nothing crosses the link until the step is submitted.
        assertThat(textAt("agent-report-pipeline", "$.brief.topic")).isNull();

        service.mutate("agent-step-brief-form", Map.of("$.form.submitted", BooleanNode.TRUE));

        await(() -> "Heat pumps".equals(textAt("agent-report-pipeline", "$.brief.topic")));
        assertThat(textAt("agent-report-pipeline", "$.brief.key")).isEqualTo("Heat pumps | for: homeowners");
        assertThat(textAt("agent-report-pipeline", "$.status.next")).isEqualTo("research");
        // The parent's reply folds back into the step.
        await(() -> "research".equals(textAt("agent-step-brief-form", "$.form.pipelineNext")));
        assertThat(textAt("agent-step-brief-form", "$.io.handoff.phase")).isEqualTo("applied");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ModelSpec example(String name) throws Exception {
        for (String dir : new String[]{"../valem-ui/src/examples/", "valem-ui/src/examples/"}) {
            File f = new File(dir + name);
            if (f.exists()) {
                ObjectNode raw = (ObjectNode) MAPPER.readTree(f);
                raw.remove("_name");
                raw.remove("_description");
                return MAPPER.treeToValue(raw, ModelSpec.class);
            }
        }
        throw new IllegalStateException(name + " not found relative to working dir");
    }

    private String textAt(String modelId, String path) {
        JsonNode v = service.getFieldValue(modelId, path);
        return v == null || v.isNull() || v.isMissingNode() ? null : v.asText();
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
