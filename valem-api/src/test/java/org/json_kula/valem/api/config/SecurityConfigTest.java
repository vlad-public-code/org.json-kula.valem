package org.json_kula.valem.api.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code Content-Security-Policy} header: strict by default (audit SEC-9, correct for
 * {@code valem-api} used headless), and overridable via {@code valem.security.csp} for deployables
 * that bundle a browser UI on the same origin ({@code valem-web}, the closed sandbox) — see
 * {@link SecurityConfig}'s Javadoc. A regression here would silently CSP-block a bundled UI's own
 * script/stylesheet/WebSocket loads without any test failure elsewhere, since no other test in this
 * module renders a real browser page against the header.
 */
class SecurityConfigTest {

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Nested
    class Default {

        @Autowired TestRestTemplate rest;

        @Test
        void defaults_to_the_strict_headless_api_policy() {
            ResponseEntity<String> resp = rest.getForEntity("/actuator/health", String.class);
            assertThat(resp.getHeaders().getFirst("Content-Security-Policy"))
                    .isEqualTo("default-src 'none'; frame-ancestors 'none'");
        }
    }

    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "valem.security.csp=default-src 'self'; connect-src 'self' ws: wss:")
    @Nested
    class Overridden {

        @Autowired TestRestTemplate rest;

        @Test
        void valem_security_csp_property_overrides_the_default() {
            ResponseEntity<String> resp = rest.getForEntity("/actuator/health", String.class);
            assertThat(resp.getHeaders().getFirst("Content-Security-Policy"))
                    .isEqualTo("default-src 'self'; connect-src 'self' ws: wss:");
        }
    }
}
