package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.service.ModelService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shell for server ({@code executor: server}) effects: performs the spec-provided-URL HTTP request
 * asynchronously (on a virtual thread) with the generic {@link EgressGuard} and retry/backoff, maps
 * the response via {@code response.set} (with {@code $response}/{@code $status} bound), and folds the
 * result back through {@link ModelService#mutate}, driving the {@code statusPath} state machine.
 */
public class HttpEffectExecutor extends EffectShell {

    private final EgressGuard guard;
    private final HttpClient http;
    private final ExecutorService pool;

    public HttpEffectExecutor(ModelService service, EgressGuard guard, EffectMetrics metrics) {
        super(service, metrics);
        this.guard = guard;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)   // no cross-host redirects
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void submit(String modelId, EffectRequest.Server s) {
        pool.submit(() -> run(modelId, s));
    }

    private void run(String modelId, EffectRequest.Server s) {
        long start = startTimer();
        try {
            setPhase(modelId, s.statusPath(), s.dedupeKey(), "in_flight", null);
            URI uri = guard.check(s.url());   // config error — never retried

            HttpRequest.BodyPublisher body = s.body() != null
                    ? HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(s.body()))
                    : HttpRequest.BodyPublishers.noBody();
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(s.timeoutMs() > 0 ? s.timeoutMs() : 5000))
                    .method(s.method() != null ? s.method().toUpperCase() : "GET", body);
            for (Map.Entry<String, String> h : s.headers().entrySet()) {
                rb.header(h.getKey(), h.getValue());
            }
            HttpRequest request = rb.build();

            int maxAttempts = 1 + Math.max(0, s.retries());
            String lastError = null;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                if (attempt > 0) sleep(backoffMillis(s.backoff(), attempt));
                try {
                    // Re-resolve + re-validate the host immediately before connecting to narrow the
                    // DNS-rebinding window (audit SEC-3).
                    guard.revalidate(uri);
                    // Stream the response and stop reading past the cap, so a hostile/huge endpoint
                    // cannot exhaust the heap before the size check (audit SEC-4 / MEM-4).
                    HttpResponse<java.io.InputStream> resp =
                            http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    int status = resp.statusCode();

                    if (status < 300) {
                        byte[] bytes = readBounded(resp, guard.maxResponseBytes());
                        JsonNode response = (bytes == null || bytes.length == 0)
                                ? mapper.nullNode() : mapper.readTree(bytes);
                        JsonataBindings bindings = new JsonataBindings()
                                .bindValue("response", response)
                                .bindValue("status", mapper.getNodeFactory().numberNode(status));
                        Map<String, JsonNode> values = evalResponseSet(s.responseSet(), mapper.nullNode(), bindings);
                        applyFoldback(modelId, s.effectId(), s.statusPath(), s.dedupeKey(), values);
                        recordSuccess("server", start);
                        return;
                    }
                    // Non-success: release the connection without buffering the (untrusted) body.
                    try { resp.body().close(); } catch (java.io.IOException ignore) { /* best effort */ }
                    lastError = "http " + status;
                    if (status < 500) break;   // 4xx: client error, do not retry
                } catch (java.io.IOException | InterruptedException e) {
                    lastError = e.toString();   // transient network/timeout — retry
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
            }
            setPhase(modelId, s.statusPath(), s.dedupeKey(), "failed", lastError);
            recordFailure("server", start);

        } catch (Exception e) {
            log.warn("http effect '{}' on model '{}' failed: {}", s.effectId(), modelId, e.toString());
            setPhase(modelId, s.statusPath(), s.dedupeKey(), "failed", e.getMessage());
            recordFailure("server", start);
        }
    }

    /**
     * Reads at most {@code max} bytes from the response body, failing (without buffering the rest) if
     * the body is larger — either by an advertised {@code Content-Length} or by reading one byte past
     * the cap. Bounds heap regardless of what the (untrusted, spec-designated) endpoint streams
     * (audit SEC-4 / MEM-4).
     */
    private static byte[] readBounded(HttpResponse<java.io.InputStream> resp, long max)
            throws java.io.IOException {
        java.util.OptionalLong contentLength = resp.headers().firstValueAsLong("Content-Length");
        if (contentLength.isPresent() && contentLength.getAsLong() > max) {
            try { resp.body().close(); } catch (java.io.IOException ignore) { /* best effort */ }
            throw new EgressGuard.EgressException(
                    "response exceeds max size (" + contentLength.getAsLong() + " bytes)");
        }
        try (java.io.InputStream in = resp.body()) {
            int cap = (int) Math.min(max + 1, Integer.MAX_VALUE);
            byte[] bytes = in.readNBytes(cap);
            if (bytes.length > max) {
                throw new EgressGuard.EgressException("response exceeds max size (> " + max + " bytes)");
            }
            return bytes;
        }
    }

    /** Backoff before retry attempt {@code n} (1-based): exponential (200ms·2^(n-1)) or fixed 200ms. */
    private static long backoffMillis(String mode, int attempt) {
        long base = 200L;
        if ("exponential".equalsIgnoreCase(mode)) {
            return Math.min(base * (1L << Math.min(attempt - 1, 5)), 10_000L);
        }
        return base;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
