package org.json_kula.valem.effects.noop;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only Spring Boot bootstrap for the noop-effect e2e.
 *
 * <p>{@code valem-api} is now a headless library (no {@code @SpringBootApplication}); the
 * runnable app lives in {@code valem-web}, which this plugin module must not depend on. So the
 * e2e boots its own minimal application, scanning the {@code ...api} package to wire the real API
 * beans ({@code ModelService}, actuator {@code MeterRegistry}, …) exactly as the former
 * {@code ValemApplication} did. The noop {@code EffectKind}/{@code EffectExecutor} are still
 * discovered with zero test wiring — the kind via {@code ServiceLoader}, the executor via this
 * module's {@code AutoConfiguration.imports} — which is the whole point of the proof.
 */
@SpringBootApplication(scanBasePackages = "org.json_kula.valem.api")
public class NoopE2EBootApplication {
    // No main(): boots only under @SpringBootTest.
}
