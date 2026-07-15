package org.json_kula.valem.api.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static MockHttpServletRequest req(String ip) {
        var r = new MockHttpServletRequest("GET", "/models");
        r.setRemoteAddr(ip);
        return r;
    }

    @Test
    void allows_requests_under_the_limit() throws Exception {
        var filter = new RateLimitFilter(3, Duration.ofMinutes(1));
        var passed = new AtomicInteger();
        FilterChain chain = (rq, rs) -> passed.incrementAndGet();

        for (int i = 0; i < 3; i++) {
            var resp = new MockHttpServletResponse();
            filter.doFilter(req("1.2.3.4"), resp, chain);
            assertThat(resp.getStatus()).isEqualTo(200);
        }
        assertThat(passed.get()).isEqualTo(3);
    }

    @Test
    void rejects_request_over_the_limit_with_429_and_retry_after() throws Exception {
        var filter = new RateLimitFilter(2, Duration.ofMinutes(1));
        FilterChain chain = (rq, rs) -> { /* pass */ };

        filter.doFilter(req("5.6.7.8"), new MockHttpServletResponse(), chain);
        filter.doFilter(req("5.6.7.8"), new MockHttpServletResponse(), chain);

        var blocked = new MockHttpServletResponse();
        filter.doFilter(req("5.6.7.8"), blocked, chain);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(Long.parseLong(blocked.getHeader("Retry-After"))).isGreaterThanOrEqualTo(1);
    }

    @Test
    void limit_is_per_client_ip() throws Exception {
        var filter = new RateLimitFilter(1, Duration.ofMinutes(1));
        FilterChain chain = (rq, rs) -> { /* pass */ };

        var a1 = new MockHttpServletResponse();
        filter.doFilter(req("10.0.0.1"), a1, chain);
        assertThat(a1.getStatus()).isEqualTo(200);

        // Different IP has its own bucket — still allowed.
        var b1 = new MockHttpServletResponse();
        filter.doFilter(req("10.0.0.2"), b1, chain);
        assertThat(b1.getStatus()).isEqualTo(200);

        // First IP is now over its limit.
        var a2 = new MockHttpServletResponse();
        filter.doFilter(req("10.0.0.1"), a2, chain);
        assertThat(a2.getStatus()).isEqualTo(429);
    }
}
