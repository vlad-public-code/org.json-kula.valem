package org.json_kula.valem.api.llm;

import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyLimitingLlmClientTest {

    @Test
    void delegates_the_call_result() {
        LlmClient delegate = prompt -> "echo:" + prompt;
        var limited = new ConcurrencyLimitingLlmClient(delegate, 1);
        assertThat(limited.complete("hi")).isEqualTo("echo:hi");
    }

    @Test
    void forwards_prompt_parts_to_the_delegate_preserving_the_split() {
        // Regression: without an explicit override the inherited default flattens PromptParts to
        // concatenated() before gating, so the delegate never sees the system/sessionContext/user
        // split and its prompt caching is silently lost. The override must forward parts intact.
        AtomicReference<SpecGenerationPrompt.PromptParts> seen = new AtomicReference<>();
        LlmClient delegate = new LlmClient() {
            @Override public String complete(String prompt) { return "unused"; }
            @Override public String complete(SpecGenerationPrompt.PromptParts parts, CompletionOptions options) {
                seen.set(parts);
                return "ok";
            }
        };
        var limited = new ConcurrencyLimitingLlmClient(delegate, 1);

        var parts = new SpecGenerationPrompt.PromptParts("SYS", "SESSION", "USER");
        assertThat(limited.complete(parts, new LlmClient.CompletionOptions(null, null))).isEqualTo("ok");
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().system()).isEqualTo("SYS");
        assertThat(seen.get().sessionContext()).isEqualTo("SESSION");
        assertThat(seen.get().user()).isEqualTo("USER");
    }

    @Test
    void rejects_zero_or_negative_permits() {
        LlmClient delegate = prompt -> "x";
        assertThatThrownBy(() -> new ConcurrencyLimitingLlmClient(delegate, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serialises_calls_to_one_at_a_time() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxSeen  = new AtomicInteger();
        LlmClient delegate = prompt -> {
            int cur = inFlight.incrementAndGet();
            maxSeen.accumulateAndGet(cur, Math::max);
            try { Thread.sleep(30); } catch (InterruptedException ignored) { }
            inFlight.decrementAndGet();
            return "ok";
        };
        var limited = new ConcurrencyLimitingLlmClient(delegate, 1);

        runConcurrently(() -> limited.complete("p"), 8);

        assertThat(maxSeen.get()).isEqualTo(1); // never two calls inside the delegate at once
    }

    @Test
    void allows_configured_number_of_concurrent_calls() throws Exception {
        // A barrier of 2 only trips if two calls run the delegate at the same time; with 2 permits
        // both threads reach it and return, with 1 permit one would block and the barrier would time out.
        CyclicBarrier barrier = new CyclicBarrier(2);
        LlmClient delegate = prompt -> {
            try {
                barrier.await(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new LlmClient.LlmException("barrier not reached — calls were not concurrent", e);
            }
            return "ok";
        };
        var limited = new ConcurrencyLimitingLlmClient(delegate, 2);

        runConcurrently(() -> limited.complete("p"), 2); // completes only if both ran concurrently
    }

    /** Runs {@code task} on {@code n} threads and rethrows the first failure. */
    private static void runConcurrently(Runnable task, int n) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            Future<?>[] futures = new Future<?>[n];
            for (int i = 0; i < n; i++) futures[i] = pool.submit(task);
            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
