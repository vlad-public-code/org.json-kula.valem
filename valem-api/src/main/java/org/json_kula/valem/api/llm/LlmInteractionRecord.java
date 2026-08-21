package org.json_kula.valem.api.llm;

import java.time.Instant;
import java.util.List;

/**
 * One recorded LLM interaction. {@code system} and {@code user} hold the two halves of a
 * {@link org.json_kula.valem.core.llm.SpecGenerationPrompt.PromptParts} split when the call used one
 * ({@code system} is {@code null} for the legacy single-string path); {@code prompt} is always the
 * full text sent (their concatenation, or the raw string) for backward-compatible display.
 *
 * <p>{@code provider} and {@code model} name the LLM that answered <em>this</em> call. They are
 * per-record rather than one global "the configured model" because a routing deployment can answer
 * two calls of the same generation from two different providers. Being metadata rather than content,
 * they also survive {@code valem.llm.log.capture-content=false}.
 */
public record LlmInteractionRecord(
        Instant timestamp,
        String system,
        String user,
        String prompt,
        String response,
        String errorMessage,
        long durationMs,
        List<WebFetchFact> webFetchCalls,
        String provider,
        String model
) {
    /** Convenience for the legacy single-string path: no system/user split, unidentified model. */
    public LlmInteractionRecord(Instant timestamp, String prompt, String response,
                                String errorMessage, long durationMs, List<WebFetchFact> webFetchCalls) {
        this(timestamp, null, prompt, prompt, response, errorMessage, durationMs, webFetchCalls,
                null, null);
    }

    /** The system/user-split path, for a client that does not identify itself. */
    public LlmInteractionRecord(Instant timestamp, String system, String user, String prompt,
                                String response, String errorMessage, long durationMs,
                                List<WebFetchFact> webFetchCalls) {
        this(timestamp, system, user, prompt, response, errorMessage, durationMs, webFetchCalls,
                null, null);
    }
}
