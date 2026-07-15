package org.json_kula.valem.effects.noop;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.api.effects.EffectExecutor;
import org.json_kula.valem.api.effects.EffectMetrics;
import org.json_kula.valem.api.effects.EffectShell;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.service.ModelService;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reference shell-side {@link EffectExecutor} for the {@code noop} kind. It performs no I/O: it binds the
 * request's resolved {@code params} as {@code $response}, evaluates the effect's {@code response.set}
 * against it, and folds the result back — reusing {@link EffectShell}'s status-machine and keyed
 * compare-and-swap fold-back exactly like the built-in HTTP/LLM shells. Runs off the mutation thread on a
 * virtual thread, matching the async post-commit contract.
 *
 * <p>Registered as a Spring bean by {@link NoopEffectAutoConfiguration}, so it is collected into
 * {@code CompositeEffectExecutor}'s plugin router by kind. Being a bean is what lets it receive the
 * managed {@link ModelService} for fold-back — the reason executors are beans, not
 * {@code ServiceLoader}-discovered.
 */
public class NoopEffectExecutor extends EffectShell implements EffectExecutor {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public NoopEffectExecutor(ModelService service, EffectMetrics metrics) {
        super(service, metrics);
    }

    @Override
    public String kind() {
        return NoopEffectKind.KIND;
    }

    @Override
    public void submit(String modelId, EffectRequest.Plugin request) {
        pool.submit(() -> run(modelId, request));
    }

    private void run(String modelId, EffectRequest.Plugin p) {
        long start = startTimer();
        try {
            setPhase(modelId, p.statusPath(), p.dedupeKey(), "in_flight", null);
            // No I/O: the "response" is just the echoed, already-evaluated payload.
            JsonataBindings bindings = new JsonataBindings().bindValue("response", p.params());
            Map<String, JsonNode> values = evalResponseSet(p.responseSet(), mapper.nullNode(), bindings);
            applyFoldback(modelId, p.effectId(), p.statusPath(), p.dedupeKey(), values);
            recordSuccess(NoopEffectKind.KIND, start);
        } catch (Exception e) {
            log.warn("noop effect '{}' on model '{}' failed: {}", p.effectId(), modelId, e.toString());
            setPhase(modelId, p.statusPath(), p.dedupeKey(), "failed", e.getMessage());
            recordFailure(NoopEffectKind.KIND, start);
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
