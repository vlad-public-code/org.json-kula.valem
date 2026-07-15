package org.json_kula.valem.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Spring Security configuration.
 *
 * <p>When {@code valem.api.key} is set, every request must carry
 * {@code Authorization: Bearer <key>}; unauthenticated requests receive HTTP 401.
 *
 * <p>When the property is blank or absent the API runs in <b>open / development mode</b>:
 * all requests are permitted and a warning is logged at startup.
 * Protections against reflected attacks (HSTS, frame-options, nosniff) are applied in all modes.
 *
 * <p><b>Content-Security-Policy.</b> Defaults to {@code default-src 'none'; frame-ancestors 'none'}
 * (audit SEC-9) — correct for {@code valem-api} used headless (no first-party HTML/JS/CSS of its
 * own). Deployables that additionally bundle and serve a browser UI from the same origin —
 * {@code valem-web} and the closed sandbox — must override {@code valem.security.csp} with a
 * policy that permits their own same-origin assets (e.g.
 * {@code default-src 'self'; connect-src 'self' ws: wss:}), or the CSP blocks the UI's own script/
 * stylesheet/WebSocket loads.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String DEFAULT_CSP = "default-src 'none'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            @Value("${valem.api.key:}") String apiKey,
            @Value("${valem.security.csp:" + DEFAULT_CSP + "}") String cspDirectives,
            @Value("${valem.rate-limit.enabled:false}") boolean rateLimitEnabled,
            @Value("${valem.rate-limit.requests:100}") int rateLimitRequests,
            @Value("${valem.rate-limit.window-seconds:60}") long rateLimitWindowSeconds,
            @Value("${valem.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor)
            throws Exception {

        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .contentTypeOptions(c -> {})
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31_536_000)
                    .includeSubDomains(true))
                // Restrictive by default (audit SEC-9); UI-bundling deployables override via
                // valem.security.csp — see class Javadoc.
                .contentSecurityPolicy(csp -> csp.policyDirectives(cspDirectives)));

        // Optional per-IP rate limiting (off by default). Placed first so limiting happens
        // before authentication work is done.
        if (rateLimitEnabled) {
            log.info("Per-IP rate limiting enabled: {} requests / {}s",
                    rateLimitRequests, rateLimitWindowSeconds);
            http.addFilterBefore(
                    new org.json_kula.valem.api.filter.RateLimitFilter(
                            rateLimitRequests, java.time.Duration.ofSeconds(rateLimitWindowSeconds),
                            trustForwardedFor),
                    org.springframework.security.web.context.SecurityContextHolderFilter.class);
        }

        if (apiKey.isBlank()) {
            log.warn("valem.api.key is not configured — API is open (development mode only)");
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        } else {
            http.addFilterBefore(new ApiKeyFilter(apiKey), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                    // Liveness/readiness probes stay reachable for orchestrators without the API key;
                    // metrics/prometheus remain authenticated as they can expose internal detail.
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .anyRequest().authenticated());
        }

        return http.build();
    }

    /** Suppress the default random-password auto-configuration. */
    @Bean
    UserDetailsService noOpUserDetails() {
        return username -> { throw new UsernameNotFoundException("No local users configured"); };
    }

    // ── API-key filter ────────────────────────────────────────────────────────

    static final class ApiKeyFilter extends OncePerRequestFilter {

        private static final String BEARER_PREFIX = "Bearer ";

        private final String requiredKey;

        ApiKeyFilter(String requiredKey) { this.requiredKey = requiredKey; }

        /** Health/info probes bypass the key so orchestrators can reach them; authz permits them too. */
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String p = request.getServletPath();
            return p != null && (p.startsWith("/actuator/health") || p.equals("/actuator/info"));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {

            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
                String key = authHeader.substring(BEARER_PREFIX.length());
                if (constantTimeEquals(requiredKey, key)) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    "api-client", null,
                                    List.of(new SimpleGrantedAuthority("ROLE_API"))));
                    chain.doFilter(request, response);
                    return;
                }
            }

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid API key\"}");
        }

        /**
         * Constant-time comparison of the presented key against the required key.
         * Avoids leaking key length / matching-prefix length through response timing.
         */
        private static boolean constantTimeEquals(String required, String presented) {
            return MessageDigest.isEqual(
                    required.getBytes(StandardCharsets.UTF_8),
                    presented.getBytes(StandardCharsets.UTF_8));
        }
    }
}
