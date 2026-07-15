package org.json_kula.valem.api.llm;

import java.time.Instant;
import java.util.List;

public record LlmInteractionRecord(
        Instant timestamp,
        String prompt,
        String response,
        String errorMessage,
        long durationMs,
        List<WebFetchFact> webFetchCalls
) {}
