package org.json_kula.valem.api.effects;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer instrumentation for the effect shells. Records one timer per completed effect execution,
 * tagged by {@code kind} (server/llm/timer) and {@code outcome} (success/failure) — so the timer's
 * count gives the success/failure breakdown and its distribution gives the latency profile per kind,
 * exposed at {@code valem.effect.duration} via {@code /actuator/metrics} and {@code /prometheus}.
 */
public final class EffectMetrics {

    private final MeterRegistry registry;

    public EffectMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Records a completed effect execution. {@code startNanos} comes from {@link System#nanoTime()}. */
    public void record(String kind, String outcome, long startNanos) {
        registry.timer("valem.effect.duration", "kind", kind, "outcome", outcome)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
