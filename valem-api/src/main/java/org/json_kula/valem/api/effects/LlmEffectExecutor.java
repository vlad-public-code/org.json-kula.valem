package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.service.ModelService;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shell for {@code executor: llm} effects: calls the configured {@link LlmClient} with a state-derived
 * prompt (optionally with a structured-output schema), parses the JSON completion, and folds it back
 * via {@code response.set} (with {@code $response} bound to the parsed JSON). The completion is captured
 * as an ordinary logged mutation, so replay never re-calls the model — LLM non-determinism is isolated
 * exactly like HTTP I/O.
 */
public class LlmEffectExecutor extends EffectShell {

    private final LlmClient llmClient;   // may be null when no LLM is configured
    private final ExecutorService pool;

    public LlmEffectExecutor(ModelService service, LlmClient llmClient, EffectMetrics metrics) {
        super(service, metrics);
        this.llmClient = llmClient;
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void submit(String modelId, EffectRequest.Llm l) {
        pool.submit(() -> run(modelId, l));
    }

    private void run(String modelId, EffectRequest.Llm l) {
        long start = startTimer();
        try {
            if (llmClient == null) {
                setPhase(modelId, l.statusPath(), l.dedupeKey(), "failed", "llm not configured");
                recordFailure("llm", start);
                return;
            }
            setPhase(modelId, l.statusPath(), l.dedupeKey(), "in_flight", null);

            LlmClient.CompletionOptions options =
                    new LlmClient.CompletionOptions(l.temperature(), l.responseSchema());
            String raw = llmClient.complete(l.prompt(), options);

            JsonNode response = parseJson(raw);
            if (response == null) {
                setPhase(modelId, l.statusPath(), l.dedupeKey(), "failed",
                        "llm response was not valid JSON");
                recordFailure("llm", start);
                return;
            }
            JsonataBindings bindings = new JsonataBindings().bindValue("response", response);
            Map<String, JsonNode> values = evalResponseSet(l.responseSet(), mapper.nullNode(), bindings);
            applyFoldback(modelId, l.effectId(), l.statusPath(), l.dedupeKey(), values);
            recordSuccess("llm", start);

        } catch (Exception e) {
            log.warn("llm effect '{}' on model '{}' failed: {}", l.effectId(), modelId, e.toString());
            setPhase(modelId, l.statusPath(), l.dedupeKey(), "failed", e.getMessage());
            recordFailure("llm", start);
        }
    }

    /** Parses the completion as JSON, tolerating markdown fences / preamble by falling back to the
     *  first {@code { … }} block. Returns null if no JSON object can be recovered. */
    private JsonNode parseJson(String raw) {
        if (raw == null) return null;
        try {
            return mapper.readTree(raw);
        } catch (Exception ignore) {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return mapper.readTree(raw.substring(start, end + 1));
                } catch (Exception ignore2) {
                    return null;
                }
            }
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
