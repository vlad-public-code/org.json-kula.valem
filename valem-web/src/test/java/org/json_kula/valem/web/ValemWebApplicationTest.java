package org.json_kula.valem.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Smoke test for the runnable web deployable: the full Spring Boot context assembles with the
 * turnkey memory/filesystem defaults (no external backend, no API key) and actually serves HTTP.
 * Proves the {@code valem-api} → {@code valem-web} split boots end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ValemWebApplicationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void context_loads_and_health_endpoint_is_up() {
        // Actuator health is unauthenticated (see security-model.md); a 200 UP proves the app booted
        // and the servlet stack is serving requests.
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).contains("UP");
    }

    @Test
    void models_endpoint_is_served() {
        // The REST API is wired from the headless valem-api library: an empty registry lists as
        // an empty JSON array (default, no API key configured in this test context).
        ResponseEntity<String> models = rest.getForEntity("/models", String.class);
        assertThat(models.getStatusCode().value()).isEqualTo(200);
        assertThat(models.getBody()).isEqualTo("[]");
    }

    @Test
    void management_ui_is_served_at_root() {
        // R5.5: the bundled valem-ui SPA is served from /. Skipped when the frontend build was
        // skipped (-Dskip.frontend=true), i.e. no static/index.html on the classpath.
        boolean uiBundled = getClass().getClassLoader().getResource("static/index.html") != null;
        assumeTrue(uiBundled, "UI not bundled (frontend build skipped) — skipping root-serve check");

        ResponseEntity<String> root = rest.getForEntity("/", String.class);
        assertThat(root.getStatusCode().value()).isEqualTo(200);
        // Vite emits an SPA shell that boots the app from a hashed module in /assets.
        assertThat(root.getBody()).contains("<div id=\"root\"></div>").contains("/assets/");
    }
}
