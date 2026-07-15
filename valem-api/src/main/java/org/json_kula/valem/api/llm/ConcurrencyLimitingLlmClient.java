package org.json_kula.valem.api.llm;

import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmProgressEvent;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.Semaphore;

/**
 * Caps the number of in-flight LLM calls through a fair {@link Semaphore}. Throttled provider keys
 * (e.g. the Mistral free tier) return HTTP 429 when several generations run at once; limiting
 * concurrency — typically to {@code 1} — keeps the request rate under the provider's ceiling instead
 * of relying solely on retry/backoff.
 *
 * <p>Wraps any {@link LlmClient} and gates all four completion methods. The permit is held for the
 * whole call, including the internal tool-calling loop of {@link #completeWithTools}, so a multi-round
 * generation occupies one slot for its full duration rather than releasing between tool turns.
 *
 * <p>This limits <em>concurrency</em> (simultaneous calls), not raw request rate; sequential calls
 * are unaffected. Use a permit count of {@code 1} to serialise all LLM traffic app-wide.
 */
public final class ConcurrencyLimitingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final Semaphore semaphore;

    /**
     * @param delegate      the client to gate
     * @param maxConcurrent maximum simultaneous calls; must be {@code >= 1}
     */
    public ConcurrencyLimitingLlmClient(LlmClient delegate, int maxConcurrent) {
        if (maxConcurrent < 1)
            throw new IllegalArgumentException("maxConcurrent must be >= 1, was " + maxConcurrent);
        this.delegate  = delegate;
        this.semaphore = new Semaphore(maxConcurrent, true); // fair → FIFO, avoids starvation
    }

    @Override
    public String complete(String prompt) throws LlmException {
        return gated(() -> delegate.complete(prompt));
    }

    @Override
    public String complete(String prompt, double temperature) throws LlmException {
        return gated(() -> delegate.complete(prompt, temperature));
    }

    @Override
    public String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor)
            throws LlmException {
        return gated(() -> delegate.completeWithTools(prompt, tools, executor));
    }

    @Override
    public String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor,
                                    double temperature) throws LlmException {
        return gated(() -> delegate.completeWithTools(prompt, tools, executor, temperature));
    }

    @Override
    public String complete(String prompt, CompletionOptions options) throws LlmException {
        return gated(() -> delegate.complete(prompt, options));
    }

    @Override
    public String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor,
                                    CompletionOptions options) throws LlmException {
        return gated(() -> delegate.completeWithTools(prompt, tools, executor, options));
    }

    @Override
    public String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor,
                                    CompletionOptions options, Consumer<LlmProgressEvent> onProgress)
            throws LlmException {
        return gated(() -> delegate.completeWithTools(prompt, tools, executor, options, onProgress));
    }

    private String gated(Supplier<String> call) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting for an LLM concurrency permit", e);
        }
        try {
            return call.get();
        } finally {
            semaphore.release();
        }
    }
}
