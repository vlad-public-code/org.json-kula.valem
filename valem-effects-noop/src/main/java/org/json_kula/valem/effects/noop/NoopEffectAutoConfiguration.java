package org.json_kula.valem.effects.noop;

import org.json_kula.valem.api.effects.EffectMetrics;
import org.json_kula.valem.service.ModelService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@code noop} effect executor as a bean when the host is a Valem API context
 * ({@link ModelService} + {@link EffectMetrics} present). Listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, so dropping
 * this jar on the classpath is enough — the bean is collected into {@code CompositeEffectExecutor}'s
 * plugin router with no wiring changes in the host application.
 */
@AutoConfiguration
public class NoopEffectAutoConfiguration {

    @Bean
    @ConditionalOnBean({ModelService.class, EffectMetrics.class})
    @ConditionalOnMissingBean(NoopEffectExecutor.class)
    public NoopEffectExecutor noopEffectExecutor(ModelService modelService, EffectMetrics effectMetrics) {
        return new NoopEffectExecutor(modelService, effectMetrics);
    }
}
