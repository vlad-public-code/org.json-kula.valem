package org.json_kula.valem.api.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.function.Supplier;

/**
 * Transparent retry-with-backoff for transient LLM transport failures (HTTP 429 rate limits and 5xx
 * server errors). The tool-calling / web-fetch path multiplies the number of LLM round-trips per
 * generation, so transient 429s are common on throttled keys; without a retry a single 429 aborts the
 * whole generation. This retries the HTTP call (not the generation) with exponential backoff and
 * jitter, honouring the {@code Retry-After} header when the server provides one.
 *
 * <p>Non-retryable errors (other 4xx — auth, bad request) propagate immediately.
 */
final class LlmRetry {

    private static final Logger log = LoggerFactory.getLogger(LlmRetry.class);

    // 6 retries → backoffs ~1,2,4,8,16,30s (cumulative ~60s, plus jitter). The tool-calling loop
    // (web_search/web_fetch/eval_jsonata) bursts many LLM round-trips per generation, so a throttled
    // key's per-minute 429 window can outlast a shorter budget; ~60s rides it out.
    private static final int  MAX_RETRIES = 6;        // total attempts = 1 + MAX_RETRIES
    private static final long BASE_MS     = 1_000;    // first backoff
    private static final long MAX_WAIT_MS = 30_000;   // cap per backoff

    private LlmRetry() {}

    /** Runs {@code call}, retrying on 429/5xx up to {@link #MAX_RETRIES} times with backoff. */
    static <T> T withRetry(Supplier<T> call, String what) {
        return withRetry(call, what, MAX_RETRIES, BASE_MS);
    }

    /** Configurable variant (used by tests with a tiny base backoff so retries don't actually wait). */
    static <T> T withRetry(Supplier<T> call, String what, int maxRetries, long baseMs) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (HttpStatusCodeException e) {   // covers 4xx (HttpClientErrorException) and 5xx
                if (!isRetryable(e) || attempt >= maxRetries) throw e;
                attempt++;
                long waitMs = backoffMillis(e, attempt, baseMs);
                log.warn("{}: {} — retrying in {} ms ({}/{})",
                        what, e.getStatusCode(), waitMs, attempt, maxRetries);
                sleep(waitMs);
            }
        }
    }

    private static boolean isRetryable(HttpStatusCodeException e) {
        int code = e.getStatusCode().value();
        return code == 429 || (code >= 500 && code < 600);
    }

    private static long backoffMillis(HttpStatusCodeException e, int attempt, long baseMs) {
        // Honour Retry-After (delta-seconds) when present.
        if (e.getResponseHeaders() != null) {
            String retryAfter = e.getResponseHeaders().getFirst("Retry-After");
            if (retryAfter != null && !retryAfter.isBlank()) {
                try {
                    return Math.min(MAX_WAIT_MS, Long.parseLong(retryAfter.trim()) * 1_000L);
                } catch (NumberFormatException ignore) { /* not a delta-seconds value — fall through */ }
            }
        }
        long exponential = baseMs * (1L << (attempt - 1));           // base, 2×, 4×, 8× …
        long jitter      = (long) (Math.random() * baseMs);
        return Math.min(MAX_WAIT_MS, exponential + jitter);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before LLM retry", e);
        }
    }
}
