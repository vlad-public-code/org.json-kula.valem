package org.json_kula.valem.api.config;

import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link ModelService} Spring bean.
 *
 * <p>The {@link ModelRegistry} bean is auto-detected via {@code @Component} on
 * {@link org.json_kula.valem.api.registry.ModelRegistry}.
 */
@Configuration
public class ServiceConfig {

    @Bean
    public ModelService modelService(ModelRegistry registry, BlobStore blobStore,
            @Value("${valem.mutation-queue-size:10}") int mutationQueueSize,
            @Value("${valem.max-models:1000}") int maxModels) {
        return new ModelService(registry, blobStore, mutationQueueSize, maxModels);
    }
}
