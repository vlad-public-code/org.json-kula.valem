package org.json_kula.valem.api.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.json_kula.valem.service.ModelService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Valem-specific Micrometer meters. Counters and timers ({@code valem.mutation.*},
 * {@code valem.effects.dispatched}, {@code valem.audit.records}) are recorded inline by
 * {@code ModelController}; the registered-model count is a gauge polled from the registry here.
 *
 * <p>Exposed via {@code /actuator/metrics} and {@code /actuator/prometheus} (Actuator).
 */
@Configuration
public class MetricsConfig {

    /** Live gauge of the number of registered models. */
    @Bean
    public MeterBinder valemModelsGauge(ModelService modelService) {
        return registry -> Gauge.builder("valem.models.registered", modelService,
                        svc -> svc.listModels().size())
                .description("Number of models currently registered")
                .register(registry);
    }
}
