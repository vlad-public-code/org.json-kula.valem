package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.core.engine.EffectDispatcher;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared machinery for the imperative-shell effect executors: driving the {@code statusPath} state
 * machine ({@code in_flight → applied | failed}) and evaluating {@code response.set} fold-back
 * expressions. Concrete shells (HTTP, LLM, timer) perform their own impure work and fold results back
 * via {@link ModelService#mutate} — always outside the fire cycle, on their own threads.
 *
 * <p>Public so plugin {@link EffectExecutor} implementations in other modules can extend it and reuse
 * the fold-back / status-machine machinery instead of re-implementing the keyed compare-and-swap.
 */
public abstract class EffectShell {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ModelService service;
    protected final ObjectMapper mapper = new ObjectMapper();
    protected final ExpressionCache expressions = new ExpressionCache();
    protected final EffectMetrics metrics;

    protected EffectShell(ModelService service, EffectMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    /** Start marker for an effect execution timer (see {@link EffectMetrics}). */
    protected long startTimer() { return System.nanoTime(); }

    /** Records a successful effect execution of {@code kind} started at {@code startNanos}. */
    protected void recordSuccess(String kind, long startNanos) {
        metrics.record(kind, "success", startNanos);
    }

    /** Records a failed effect execution of {@code kind} started at {@code startNanos}. */
    protected void recordFailure(String kind, long startNanos) {
        metrics.record(kind, "failure", startNanos);
    }

    /** Evaluates each {@code response.set} value expression against {@code context} with {@code bindings}. */
    protected Map<String, JsonNode> evalResponseSet(Map<String, String> set, JsonNode context,
                                                    JsonataBindings bindings) throws Exception {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : set.entrySet()) {
            JsonNode ctx = context != null ? context : mapper.nullNode();
            JsonNode v = expressions.get(e.getValue()).evaluate(ctx, bindings);
            out.put(e.getKey(), v != null ? v : mapper.nullNode());
        }
        return out;
    }

    /**
     * Folds a successful result back with a keyed compare-and-swap: hands the applied writes
     * ({@code responseValues} + {@code applied} status) and the cancelled writes to
     * {@link ModelService#completeFoldback}, which atomically decides — apply if still current, discard
     * and re-fire if superseded by a newer input, or cancel if the trigger no longer holds. This is what
     * makes an in-flight effect whose input changed (or whose precondition went away, e.g. a timer whose
     * ticket was closed) safe: the stale result is never written.
     */
    protected void applyFoldback(String modelId, String effectId, String statusPath, JsonNode firedKey,
                                 Map<String, JsonNode> responseValues) {
        Map<String, JsonNode> applied = new LinkedHashMap<>(responseValues);
        appendStatus(applied, statusPath, firedKey, "applied", null);

        Map<String, JsonNode> cancelled = new LinkedHashMap<>();
        if (statusPath != null && !statusPath.isBlank()) {
            cancelled.put(statusPath + ".phase", TextNode.valueOf("cancelled"));
            cancelled.put(statusPath + ".at", TextNode.valueOf(Instant.now().toString()));
            cancelled.put(statusPath + ".key", mapper.nullNode());   // clear the key so the effect can re-arm later
        }

        EffectDispatcher.FoldbackDecision decision =
                service.completeFoldback(modelId, effectId, firedKey, applied, cancelled);
        if (decision != EffectDispatcher.FoldbackDecision.CURRENT) {
            log.info("effect '{}' fold-back {} (input/precondition changed while in flight)", effectId, decision);
        }
    }

    /** Writes a {@code statusPath} phase transition as its own mutation. */
    protected void setPhase(String modelId, String statusPath, JsonNode dedupeKey, String phase, String error) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        if (!appendStatus(m, statusPath, dedupeKey, phase, error)) return;
        try {
            service.mutate(modelId, m);
        } catch (Exception e) {
            log.warn("could not set effect phase '{}' for '{}': {}", phase, modelId, e.toString());
        }
    }

    /** Appends the {@code statusPath} fields for a phase into {@code target}; false if no statusPath. */
    protected boolean appendStatus(Map<String, JsonNode> target, String statusPath, JsonNode dedupeKey,
                                   String phase, String error) {
        if (statusPath == null || statusPath.isBlank()) return false;
        target.put(statusPath + ".phase", TextNode.valueOf(phase));
        target.put(statusPath + ".at", TextNode.valueOf(Instant.now().toString()));
        if (error != null) {
            target.put(statusPath + ".error", TextNode.valueOf(error));
        }
        // Record the edge key so a replay / re-fire of the same value is deduped by the guard.
        if (dedupeKey != null && (phase.equals("in_flight") || phase.equals("applied"))) {
            target.put(statusPath + ".key", dedupeKey);
        }
        return true;
    }
}
