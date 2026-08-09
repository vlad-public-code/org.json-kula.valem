package org.json_kula.valem.api.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Publishes the {@code valem.limits.*} bounds as JVM system properties, so a deployment configures
 * them in {@code application.yml} like every other {@code valem.*} setting.
 *
 * <p>Those bounds are enforced inside {@code valem-core}, which has no Spring dependency and so reads
 * them with {@link Integer#getInteger} in a static initialiser. Left alone that makes them the one
 * family of settings a deployer can only pass as {@code -D} flags — invisible in the config file, and
 * awkward on a platform that injects environment variables rather than JVM arguments. Copying the
 * resolved Spring value into the system property makes {@code application.yml} the source of truth and
 * gives the bounds Boot's usual relaxed binding, including {@code VALEM_LIMITS_EXPRESSION_CACHE_SIZE}.
 *
 * <p>An explicit {@code -D} still wins: it is left untouched here, and it also outranks the config
 * file in the {@link ConfigurableEnvironment} this reads from.
 *
 * <p>An {@link EnvironmentPostProcessor} rather than a {@code @Configuration} bean because the core
 * values are captured in static initialisers: this runs while the environment is being prepared,
 * before any bean — and therefore before any core class — can be loaded. {@link Ordered#LOWEST_PRECEDENCE}
 * puts it after Boot's own config-data processing, so {@code application.yml} has already been
 * contributed. Registered in {@code META-INF/spring.factories}, which is how Boot discovers an
 * {@code EnvironmentPostProcessor} — the {@code META-INF/spring/*.imports} files cover
 * auto-configuration only.
 */
public class CoreLimitsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * The core bounds bridged into system properties — every {@code valem.limits.*} setting listed in
     * {@code docs/deployment/configuration.md}. A malformed value is ignored by the core reader rather
     * than thrown, so a bad config entry degrades to the core default instead of stopping the engine.
     */
    static final String[] BRIDGED_PROPERTIES = {
            "valem.limits.expression-cache-size",
            "valem.limits.max-array-index",
            "valem.limits.regex-max-input",
            "valem.limits.regex-timeout-ms",
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (String property : BRIDGED_PROPERTIES) {
            if (System.getProperty(property) != null) continue;   // an explicit -D wins
            String configured = environment.getProperty(property);
            if (configured != null && !configured.isBlank()) {
                System.setProperty(property, configured.trim());
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
