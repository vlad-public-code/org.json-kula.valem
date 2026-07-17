package org.json_kula.valem.api.llm;

import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmProgressEvent;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class RecordingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final LlmInteractionLog log;

    public RecordingLlmClient(LlmClient delegate, LlmInteractionLog log) {
        this.delegate = delegate;
        this.log = log;
    }

    @Override
    public String complete(String prompt) throws LlmException {
        return doRecord(prompt, () -> delegate.complete(prompt), List::of);
    }

    @Override
    public String complete(String prompt, double temperature) throws LlmException {
        return doRecord(prompt, () -> delegate.complete(prompt, temperature), List::of);
    }

    @Override
    public String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor)
            throws LlmException {
        Supplier<List<WebFetchFact>> getFacts =
                executor instanceof FactProvider fp ? fp::facts : List::of;
        return doRecord(prompt, () -> delegate.completeWithTools(prompt, tools, executor), getFacts);
    }

    @Override
    public String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor,
                                    double temperature) throws LlmException {
        Supplier<List<WebFetchFact>> getFacts =
                executor instanceof FactProvider fp ? fp::facts : List::of;
        return doRecord(prompt,
                () -> delegate.completeWithTools(prompt, tools, executor, temperature), getFacts);
    }

    @Override
    public String complete(String prompt, CompletionOptions options) throws LlmException {
        return doRecord(prompt, () -> delegate.complete(prompt, options), List::of);
    }

    @Override
    public String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor,
                                    CompletionOptions options) throws LlmException {
        Supplier<List<WebFetchFact>> getFacts =
                executor instanceof FactProvider fp ? fp::facts : List::of;
        return doRecord(prompt,
                () -> delegate.completeWithTools(prompt, tools, executor, options), getFacts);
    }

    @Override
    public String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor,
                                    CompletionOptions options, Consumer<LlmProgressEvent> onProgress)
            throws LlmException {
        Supplier<List<WebFetchFact>> getFacts =
                executor instanceof FactProvider fp ? fp::facts : List::of;
        return doRecord(prompt,
                () -> delegate.completeWithTools(prompt, tools, executor, options, onProgress), getFacts);
    }

    // ── PromptParts (system/user split) — record the two halves separately ──────

    @Override
    public String complete(SpecGenerationPrompt.PromptParts parts, CompletionOptions options)
            throws LlmException {
        return doRecord(parts.system(), parts.user(), parts.concatenated(),
                () -> delegate.complete(parts, options), List::of);
    }

    @Override
    public String completeWithTools(SpecGenerationPrompt.PromptParts parts, List<ToolDefinition> tools,
                                    ToolExecutor executor, CompletionOptions options,
                                    Consumer<LlmProgressEvent> onProgress) throws LlmException {
        Supplier<List<WebFetchFact>> getFacts =
                executor instanceof FactProvider fp ? fp::facts : List::of;
        return doRecord(parts.system(), parts.user(), parts.concatenated(),
                () -> delegate.completeWithTools(parts, tools, executor, options, onProgress), getFacts);
    }

    private String doRecord(String prompt, LlmCallable call, Supplier<List<WebFetchFact>> getFacts) {
        return doRecord(null, prompt, prompt, call, getFacts);
    }

    private String doRecord(String system, String user, String prompt, LlmCallable call,
                            Supplier<List<WebFetchFact>> getFacts) {
        long start = System.currentTimeMillis();
        try {
            String response = call.call();
            log.record(new LlmInteractionRecord(Instant.now(), system, user, prompt, response, null,
                    System.currentTimeMillis() - start, getFacts.get()));
            return response;
        } catch (LlmException e) {
            log.record(new LlmInteractionRecord(Instant.now(), system, user, prompt, null, e.getMessage(),
                    System.currentTimeMillis() - start, getFacts.get()));
            throw e;
        }
    }

    @FunctionalInterface
    private interface LlmCallable {
        String call() throws LlmException;
    }
}
