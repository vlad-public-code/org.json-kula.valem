package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.TargetSpec;

import java.util.Map;

/**
 * A fully-resolved effect request emitted by {@link EffectDispatcher} during the pure cycle and
 * handed to an {@link EffectDispatcher.EffectSink} post-commit. Carries only data — no I/O has
 * happened yet. The shell selected by the concrete subtype performs (or surfaces) the effect and,
 * for {@link Server}, folds the response back as a new mutation.
 */
public sealed interface EffectRequest
        permits EffectRequest.Server, EffectRequest.Caller, EffectRequest.Llm, EffectRequest.Timer,
                EffectRequest.Plugin {

    String effectId();

    /** Address of the {@code $io} status sub-document the shell updates (may be {@code null}). */
    String statusPath();

    /** Evaluated edge key that caused this fire (may be {@code null} when no {@code dedupeKey}). */
    JsonNode dedupeKey();

    /**
     * Server-executed effect. Three mutually-exclusive shapes, distinguished by which locator is set:
     * <ul>
     *   <li><b>HTTP</b> — {@code url} is set: perform the HTTP request, map {@code responseSet}, fold
     *       back (the existing external-effect path).</li>
     *   <li><b>write-link</b> — {@code targetRef} + {@code targetPath} + {@code targetBody}: mutate the
     *       resolved model and fold its reply back (composition).</li>
     *   <li><b>read-link</b> — {@code targetRef} + {@code targetRead}: read the value at that address
     *       (no mutation) and fold it back.</li>
     * </ul>
     * {@code binding} is an optional escape hatch overriding repository resolution for an external
     * model. Still pure data — the shell resolves the ref and picks {@code mutate} vs {@code getField}.
     */
    record Server(
            String effectId,
            String method,
            String url,                       // HTTP form: interpolated, relative to the egress profile base
            Map<String, String> headers,      // interpolated
            JsonNode body,                    // evaluated (null for GET / bodyless)
            Map<String, String> responseSet,  // JSON Path target -> JSONata over $response (unresolved)
            String egressProfile,
            int timeoutMs,
            int retries,
            String backoff,
            String statusPath,
            JsonNode dedupeKey,
            ModelCoordinate targetRef,        // link forms: the resolved-later target coordinate (null for HTTP)
            String targetPath,                // write-link: canonical address in the target to mutate
            JsonNode targetBody,              // write-link: evaluated value written at targetPath
            String targetRead,                // read-link: canonical address in the target to read
            boolean watch,                    // read-link: standing subscription (later)
            TargetSpec.Binding binding        // optional external-model escape hatch
    ) implements EffectRequest {

        /** {@code true} when this is a composition link (write or read), not an HTTP request. */
        public boolean isLink() { return targetRef != null; }

        /** {@code true} for a read-link (reads the target, no mutation). */
        public boolean isReadLink() { return targetRef != null && targetRead != null; }

        /** {@code true} for a write-link (mutates the target, folds the reply). */
        public boolean isWriteLink() { return targetRef != null && targetRead == null; }

        /** Constructs the HTTP form (no target). */
        public static Server http(String effectId, String method, String url, Map<String, String> headers,
                                  JsonNode body, Map<String, String> responseSet, String egressProfile,
                                  int timeoutMs, int retries, String backoff, String statusPath,
                                  JsonNode dedupeKey) {
            return new Server(effectId, method, url, headers, body, responseSet, egressProfile,
                    timeoutMs, retries, backoff, statusPath, dedupeKey,
                    null, null, null, null, false, null);
        }

        /** Constructs a write-link (mutate {@code targetPath} with {@code targetBody}). */
        public static Server writeLink(String effectId, ModelCoordinate targetRef, String targetPath,
                                       JsonNode targetBody, Map<String, String> responseSet,
                                       int timeoutMs, int retries, String backoff, String statusPath,
                                       JsonNode dedupeKey, TargetSpec.Binding binding) {
            return new Server(effectId, "LINK", null, Map.of(), targetBody, responseSet, null,
                    timeoutMs, retries, backoff, statusPath, dedupeKey,
                    targetRef, targetPath, targetBody, null, false, binding);
        }

        /** Constructs a read-link (read {@code targetRead}, no mutation). */
        public static Server readLink(String effectId, ModelCoordinate targetRef, String targetRead,
                                      boolean watch, Map<String, String> responseSet,
                                      int timeoutMs, int retries, String backoff, String statusPath,
                                      JsonNode dedupeKey, TargetSpec.Binding binding) {
            return new Server(effectId, "LINK", null, Map.of(), null, responseSet, null,
                    timeoutMs, retries, backoff, statusPath, dedupeKey,
                    targetRef, null, null, targetRead, watch, binding);
        }
    }

    /** Caller-executed command: surfaced in the mutation response for the client to run. No egress. */
    record Caller(
            String effectId,
            String emit,
            JsonNode payload,
            String statusPath,
            JsonNode dedupeKey
    ) implements EffectRequest {}

    /** LLM-executed effect: call a language model with {@code prompt}, parse the JSON completion, and
     *  fold it back via {@code responseSet} (with {@code $response} bound to the parsed JSON). */
    record Llm(
            String effectId,
            String prompt,                    // resolved prompt text
            JsonNode responseSchema,          // optional structured-output schema (null = plain JSON)
            Map<String, String> responseSet,  // JSON Path target -> JSONata over $response (unresolved)
            String model,                     // optional model override
            Double temperature,               // optional sampling temperature
            String statusPath,
            JsonNode dedupeKey
    ) implements EffectRequest {}

    /** Timer-executed effect: schedule the {@code responseSet} fold-back at a future time. Exactly one
     *  of {@code fireAtEpochMillis} (absolute) / {@code delayMillis} (relative) is set; the shell owns
     *  the clock and records the resolved fire time in the statusPath. */
    record Timer(
            String effectId,
            Long fireAtEpochMillis,           // absolute fire time (from `at`), or null
            Long delayMillis,                 // relative delay (from `afterMs`), or null
            Map<String, String> responseSet,  // JSON Path target -> JSONata evaluated at fire time
            String statusPath,
            JsonNode dedupeKey
    ) implements EffectRequest {}

    /**
     * Generic carrier for a <b>plugin-provided</b> effect kind (any {@code executor} string that is not
     * one of the four built-ins). A pure {@link org.json_kula.valem.core.engine.spi.EffectKind}
     * resolves the {@link org.json_kula.valem.core.model.EffectSpec} into {@code params} — already
     * evaluated, kind-specific data — and the shell {@code EffectExecutor} registered for {@code kind}
     * reads {@code params} to perform the I/O, folding any {@code responseSet} back as a mutation. This
     * one carrier serves every plugin kind, so a new kind needs no new {@code EffectRequest} subtype.
     */
    record Plugin(
            String kind,                      // the executor string this request was resolved for
            String effectId,
            String statusPath,
            JsonNode dedupeKey,
            Map<String, String> responseSet,  // shared fold-back mapping (JSON Path -> JSONata over $response)
            ObjectNode params                 // resolved kind-specific payload the executor reads
    ) implements EffectRequest {}
}
