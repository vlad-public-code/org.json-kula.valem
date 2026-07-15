package org.json_kula.valem.api.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Component
public class LlmInteractionLog {

    private static final int MAX_SIZE = 50;
    private static final String REDACTED = "[redacted]";

    private final Deque<LlmInteractionRecord> entries = new ArrayDeque<>();

    /**
     * When {@code false} ({@code valem.llm.log.capture-content=false}) the prompt/response text
     * is dropped and only metadata (timing, timestamp, error presence) is retained, so potentially
     * sensitive domain data does not sit in memory or surface at {@code /llm/interactions}
     * (audit SEC-10). Defaults to {@code true} (capture full content).
     */
    private final boolean captureContent;

    public LlmInteractionLog(@Value("${valem.llm.log.capture-content:true}") boolean captureContent) {
        this.captureContent = captureContent;
    }

    public synchronized void record(LlmInteractionRecord entry) {
        entries.addFirst(captureContent ? entry : redact(entry));
        if (entries.size() > MAX_SIZE) entries.removeLast();
    }

    private static LlmInteractionRecord redact(LlmInteractionRecord e) {
        return new LlmInteractionRecord(
                e.timestamp(),
                REDACTED,
                e.response() == null ? null : REDACTED,
                e.errorMessage() == null ? null : REDACTED,
                e.durationMs(),
                List.of());
    }

    public synchronized List<LlmInteractionRecord> getAll() {
        return new ArrayList<>(entries);
    }
}
