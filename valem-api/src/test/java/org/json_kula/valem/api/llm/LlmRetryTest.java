package org.json_kula.valem.api.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRetryTest {

    private static HttpClientErrorException client(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(),
                HttpHeaders.EMPTY, new byte[0], null);
    }

    // Tiny base backoff (1 ms) so the retry loop doesn't actually wait in tests.
    private static <T> T fast(java.util.function.Supplier<T> call) {
        return LlmRetry.withRetry(call, "test", 4, 1L);
    }

    @Test
    void retries_on_429_then_succeeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = fast(() -> {
            if (calls.incrementAndGet() == 1) throw client(HttpStatus.TOO_MANY_REQUESTS);
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2); // one failure + one success
    }

    @Test
    void retries_on_5xx_then_succeeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = fast(() -> {
            if (calls.incrementAndGet() == 1)
                throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null);
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void does_not_retry_non_retryable_4xx() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> fast(() -> {
            calls.incrementAndGet();
            throw client(HttpStatus.UNAUTHORIZED);
        })).isInstanceOf(HttpClientErrorException.class);
        assertThat(calls.get()).isEqualTo(1); // immediate, no retry
    }

    @Test
    void gives_up_after_max_retries_and_rethrows() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> fast(() -> {
            calls.incrementAndGet();
            throw client(HttpStatus.TOO_MANY_REQUESTS);
        })).isInstanceOf(HttpClientErrorException.class);
        assertThat(calls.get()).isEqualTo(5); // 1 initial + 4 retries
    }
}
