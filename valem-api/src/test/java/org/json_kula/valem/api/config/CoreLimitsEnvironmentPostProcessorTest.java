package org.json_kula.valem.api.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bridge that lets {@code valem.limits.*} be configured in {@code application.yml} even though
 * {@code valem-core} enforces those bounds without Spring, reading them as system properties in a
 * static initialiser.
 */
class CoreLimitsEnvironmentPostProcessorTest {

    private static final String CACHE_SIZE = "valem.limits.expression-cache-size";

    private final CoreLimitsEnvironmentPostProcessor processor = new CoreLimitsEnvironmentPostProcessor();

    @BeforeEach
    @AfterEach
    void clearBridgedProperties() {
        for (String property : CoreLimitsEnvironmentPostProcessor.BRIDGED_PROPERTIES) {
            System.clearProperty(property);
        }
    }

    private StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    @Test
    void a_configured_limit_becomes_the_system_property_core_reads() {
        processor.postProcessEnvironment(environmentWith(Map.of(CACHE_SIZE, "1200")), new SpringApplication());

        assertThat(System.getProperty(CACHE_SIZE)).isEqualTo("1200");
    }

    @Test
    void every_documented_core_limit_is_bridged() {
        processor.postProcessEnvironment(environmentWith(Map.of(
                CACHE_SIZE, "1200",
                "valem.limits.max-array-index", "2000000",
                "valem.limits.regex-max-input", "50000",
                "valem.limits.regex-timeout-ms", "250")), new SpringApplication());

        assertThat(System.getProperty("valem.limits.max-array-index")).isEqualTo("2000000");
        assertThat(System.getProperty("valem.limits.regex-max-input")).isEqualTo("50000");
        assertThat(System.getProperty("valem.limits.regex-timeout-ms")).isEqualTo("250");
    }

    @Test
    void an_explicit_jvm_flag_outranks_the_config_file() {
        // -D is how these were configured before they were config-file settings; a deployment that
        // still passes one must not have it silently rewritten by the shipped default.
        System.setProperty(CACHE_SIZE, "64");

        processor.postProcessEnvironment(environmentWith(Map.of(CACHE_SIZE, "1200")), new SpringApplication());

        assertThat(System.getProperty(CACHE_SIZE)).isEqualTo("64");
    }

    @Test
    void a_blank_or_absent_value_leaves_the_core_default_in_place() {
        processor.postProcessEnvironment(environmentWith(Map.of(CACHE_SIZE, "   ")), new SpringApplication());

        assertThat(System.getProperty(CACHE_SIZE)).isNull();
    }

    @Test
    void the_shipped_application_yml_publishes_the_expression_cache_bound() {
        // End-to-end over the real config file and the real .imports registration: booting an
        // application resolves ${VALEM_LIMITS_EXPRESSION_CACHE_SIZE:500} and publishes it before any
        // bean — and so before any valem-core class — can be loaded.
        SpringApplication application = new SpringApplication(EmptyConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        String fromEnvironment;
        try (ConfigurableApplicationContext context = application.run()) {
            fromEnvironment = context.getEnvironment().getProperty(CACHE_SIZE);
        }

        String expected = System.getenv("VALEM_LIMITS_EXPRESSION_CACHE_SIZE");
        if (expected == null || expected.isBlank()) expected = "500";
        assertThat(fromEnvironment).isEqualTo(expected);
        assertThat(System.getProperty(CACHE_SIZE)).isEqualTo(expected);
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
