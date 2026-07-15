package org.json_kula.valem.api.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.json_kula.valem.api.effects.CompositeEffectExecutor;
import org.json_kula.valem.api.effects.EffectExecutor;
import org.json_kula.valem.api.effects.EffectMetrics;
import org.json_kula.valem.api.effects.EgressGuard;
import org.json_kula.valem.api.effects.HttpEffectExecutor;
import org.json_kula.valem.api.effects.LinkEffectExecutor;
import org.json_kula.valem.api.effects.LlmEffectExecutor;
import org.json_kula.valem.api.effects.TimerEffectExecutor;
import org.json_kula.valem.api.authz.EffectApprovalRegistry;
import org.json_kula.valem.api.reference.ModelResolver;
import org.json_kula.valem.api.reference.WatchManager;
import org.json_kula.valem.core.engine.spi.EffectKindRegistry;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.service.ModelService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Wires the effect executors (HTTP / LLM / timer) behind a {@link CompositeEffectExecutor} and
 * registers it on {@link ModelService}. Effect URLs/prompts/timings are spec-provided; SSRF for the
 * HTTP shell is handled by the generic {@link EgressGuard}. The LLM shell is wired with whatever
 * {@link LlmClient} bean exists (or {@code null} when the LLM is not configured).
 */
@Configuration
public class EffectsConfig {

    @Bean
    public EgressGuard egressGuard(
            @Value("${valem.effects.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${valem.effects.allow-insecure-http:false}") boolean allowInsecureHttp,
            @Value("${valem.effects.max-response-bytes:1048576}") long maxResponseBytes,
            @Value("${valem.effects.allowed-hosts:}") String allowedHosts) {
        return new EgressGuard(allowPrivateIps, allowInsecureHttp, maxResponseBytes,
                parseAllowedHosts(allowedHosts));
    }

    /** Parses the comma-separated {@code valem.effects.allowed-hosts} list (blank => any host). */
    private static java.util.Set<String> parseAllowedHosts(String csv) {
        if (csv == null || csv.isBlank()) return java.util.Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    @Bean
    public EffectMetrics effectMetrics(MeterRegistry registry) {
        return new EffectMetrics(registry);
    }

    @Bean
    public HttpEffectExecutor httpEffectExecutor(ModelService modelService, EgressGuard egressGuard,
                                                 EffectMetrics effectMetrics) {
        return new HttpEffectExecutor(modelService, egressGuard, effectMetrics);
    }

    @Bean
    public LlmEffectExecutor llmEffectExecutor(ModelService modelService,
                                               ObjectProvider<LlmClient> llmClient,
                                               EffectMetrics effectMetrics) {
        return new LlmEffectExecutor(modelService, llmClient.getIfAvailable(), effectMetrics);
    }

    @Bean
    public TimerEffectExecutor timerEffectExecutor(ModelService modelService, EffectMetrics effectMetrics) {
        return new TimerEffectExecutor(modelService, effectMetrics);
    }

    @Bean
    public WatchManager watchManager(ModelService modelService) {
        return new WatchManager(modelService);
    }

    @Bean
    public LinkEffectExecutor linkEffectExecutor(ModelService modelService, ModelResolver modelResolver,
                                                 EffectMetrics effectMetrics, WatchManager watchManager) {
        return new LinkEffectExecutor(modelService, modelResolver, effectMetrics, watchManager);
    }

    @Bean
    public CompositeEffectExecutor effectExecutor(ModelService modelService,
                                                  HttpEffectExecutor http,
                                                  LinkEffectExecutor link,
                                                  LlmEffectExecutor llm,
                                                  TimerEffectExecutor timer,
                                                  EffectApprovalRegistry effectApprovalRegistry,
                                                  ObjectProvider<EffectExecutor> pluginExecutors,
                                                  @Value("${valem.effects.kinds.enabled:}") String enabledKinds) {
        // Apply the enable-list before any spec is validated/loaded (this @Bean runs during context init,
        // well before ModelLoader's ApplicationRunner). Empty/unset => every discovered kind is enabled.
        EffectKindRegistry.configure(parseEnabledKinds(enabledKinds));

        List<EffectExecutor> plugins = pluginExecutors.orderedStream().toList();
        CompositeEffectExecutor composite = new CompositeEffectExecutor(
                http, link, llm, timer, effectApprovalRegistry, modelService, plugins);
        modelService.setEffectExecutor(composite);
        return composite;
    }

    /** Parses the comma-separated {@code valem.effects.kinds.enabled} list (blank => empty => all). */
    private static List<String> parseEnabledKinds(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
