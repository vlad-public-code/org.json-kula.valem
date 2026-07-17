package org.json_kula.valem.api.llm;

import java.time.Instant;
import java.util.List;

/**
 * One recorded LLM interaction. {@code system} and {@code user} hold the two halves of a
 * {@link org.json_kula.valem.core.llm.SpecGenerationPrompt.PromptParts} split when the call used one
 * ({@code system} is {@code null} for the legacy single-string path); {@code prompt} is always the
 * full text sent (their concatenation, or the raw string) for backward-compatible display.
 */
public record LlmInteractionRecord(
        Instant timestamp,
        String system,
        String user,
        String prompt,
        String response,
        String errorMessage,
        long durationMs,
        List<WebFetchFact> webFetchCalls
) {
    /** Convenience for the legacy single-string path: no system/user split. */
    public LlmInteractionRecord(Instant timestamp, String prompt, String response,
                                String errorMessage, long durationMs, List<WebFetchFact> webFetchCalls) {
        this(timestamp, null, prompt, prompt, response, errorMessage, durationMs, webFetchCalls);
    }
}
