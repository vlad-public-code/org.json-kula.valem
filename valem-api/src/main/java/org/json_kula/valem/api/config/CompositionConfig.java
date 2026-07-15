package org.json_kula.valem.api.config;

import org.json_kula.valem.api.composition.CompositionValidator;
import org.json_kula.valem.api.reference.HttpModelRepository;
import org.json_kula.valem.api.reference.LocalModelRepository;
import org.json_kula.valem.api.reference.ModelRepository;
import org.json_kula.valem.api.reference.ModelResolver;
import org.json_kula.valem.api.reference.RepositoryClass;
import org.json_kula.valem.api.reference.TemplateMaterializer;
import org.json_kula.valem.api.reference.mcp.McpModelRepository;
import org.json_kula.valem.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires model composition (references + links). M2 configures a single {@code local} repository — the
 * in-process registry, which also doubles as the resolution cache. The priority-ordered chain
 * ({@code valem.composition.repositories}) with {@code mcp}/{@code http}/{@code filesystem}
 * transports is added in M6; this bean's shape ({@code List<ModelRepository>} → {@link ModelResolver})
 * is already correct for that.
 */
@Configuration
@EnableConfigurationProperties(CompositionProperties.class)
public class CompositionConfig {

    private static final Logger log = LoggerFactory.getLogger(CompositionConfig.class);

    @Bean
    public LocalModelRepository localModelRepository(ModelService modelService) {
        return new LocalModelRepository(modelService);
    }

    @Bean
    public ModelResolver modelResolver(LocalModelRepository local, CompositionProperties props) {
        // Priority-ordered chain: local first (checked first, doubles as cache), then configured repos.
        List<ModelRepository> chain = new ArrayList<>();
        chain.add(local);
        for (CompositionProperties.RepositoryConfig rc : props.getRepositories()) {
            String transport = rc.getTransport() == null ? "" : rc.getTransport().toLowerCase();
            // Class is orthogonal to transport: configured explicitly, else inferred per transport.
            RepositoryClass clazz = RepositoryClass.parse(rc.getRepoClass(),
                    RepositoryClass.defaultFor(transport));
            switch (transport) {
                case "http" -> chain.add(
                        new HttpModelRepository(rc.getId(), rc.getLocator(), rc.getCredential(), clazz));
                case "mcp" -> {
                    try {
                        chain.add(McpModelRepository.launch(rc.getId(), rc.getLocator(), clazz));
                    } catch (java.io.IOException e) {
                        log.warn("could not launch mcp repository '{}' ({}): {}",
                                rc.getId(), rc.getLocator(), e.toString());
                    }
                }
                case "local", "" -> { /* the implicit local repo is already first */ }
                default -> log.warn("composition repository '{}' has unsupported transport '{}' — skipped",
                        rc.getId(), rc.getTransport());
            }
        }
        return new ModelResolver(chain);
    }

    @Bean
    public CompositionValidator compositionValidator(
            @Value("${valem.composition.lazy-binding:false}") boolean lazyBinding) {
        return new CompositionValidator(lazyBinding);
    }

    @Bean
    public TemplateMaterializer templateMaterializer(ModelResolver modelResolver) {
        return new TemplateMaterializer(modelResolver);
    }
}
