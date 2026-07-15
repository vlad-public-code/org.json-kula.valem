package org.json_kula.valem.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * Optional per-IP sliding-window rate limiter for the base API.
 *
 * <p><b>Off by default.</b> Enabled only when {@code valem.rate-limit.enabled=true}; when
 * disabled the filter is never added to the chain, so there is no behaviour change. When enabled,
 * each client IP gets a sliding window of {@code requests} per {@code window}; requests over the
 * limit receive HTTP 429 with a {@code Retry-After} header.
 *
 * <p>Mirrors the sandbox {@code RateLimitBucket} sliding-window design so the two can later converge
 * on a shared implementation.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final int      limit;
    private final Duration window;
    private final boolean  trustForwardedFor;

    // Bound the bucket map so a flood of distinct source IPs cannot grow it without limit. Caffeine
    // evicts only the coldest buckets under memory pressure (LRU-ish) rather than clearing every
    // client's counter at once — the old blanket clear() let a spoofed-IP flood reset the limiter for
    // everyone (audit SEC-6).
    private static final int MAX_BUCKETS = 100_000;
    private final Cache<String, Bucket> buckets;

    public RateLimitFilter(int requestsPerWindow, Duration window) {
        this(requestsPerWindow, window, false);
    }

    public RateLimitFilter(int requestsPerWindow, Duration window, boolean trustForwardedFor) {
        this.limit  = Math.max(1, requestsPerWindow);
        this.window = window;
        this.trustForwardedFor = trustForwardedFor;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS)
                .expireAfterAccess(Math.max(1, window.toSeconds() * 2), TimeUnit.SECONDS)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String clientIp = clientIp(request);
        Bucket bucket = buckets.get(clientIp, k -> new Bucket(limit, window));

        if (bucket.tryConsume()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfter = bucket.retryAfterSeconds();
        log.debug("Rate limit exceeded for {} (retry after {}s)", clientIp, retryAfter);
        response.setStatus(429); // 429 Too Many Requests (no SC_ constant in the servlet API)
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"Rate limit exceeded\",\"retryAfterSeconds\":" + retryAfter + "}");
    }

    private String clientIp(HttpServletRequest request) {
        // Honour the first X-Forwarded-For hop only when explicitly trusted (audit SEC-6): a directly
        // reachable instance must not trust a client-controlled header, or the limiter is trivially
        // bypassed by spoofing a fresh IP per request. Default is the socket remote address.
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).strip();
            }
        }
        return request.getRemoteAddr();
    }

    /** Sliding-window counter for one client IP (mirrors sandbox {@code RateLimitBucket}). */
    static final class Bucket {
        private final int limit;
        private final Duration window;
        private final ArrayDeque<Instant> timestamps = new ArrayDeque<>();

        Bucket(int limit, Duration window) {
            this.limit  = limit;
            this.window = window;
        }

        synchronized boolean tryConsume() {
            Instant now = Instant.now();
            evictExpired(now);
            if (timestamps.size() >= limit) return false;
            timestamps.add(now);
            return true;
        }

        synchronized long retryAfterSeconds() {
            if (timestamps.isEmpty()) return 1;
            Instant expiry = timestamps.peek().plus(window);
            return Math.max(1, Duration.between(Instant.now(), expiry).toSeconds());
        }

        private void evictExpired(Instant now) {
            Instant cutoff = now.minus(window);
            while (!timestamps.isEmpty() && timestamps.peek().isBefore(cutoff)) {
                timestamps.poll();
            }
        }
    }

    /** Exposed for monitoring/testing — current number of tracked client buckets. */
    long trackedClients() {
        return buckets.estimatedSize();
    }
}
