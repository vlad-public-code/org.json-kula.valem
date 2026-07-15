package org.json_kula.valem.api.config;

import org.json_kula.valem.api.authz.EffectApprovalRegistry;
import org.json_kula.valem.service.ModelService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Wires multi-tenant authorization. M5 ships <b>inherited-effect approval</b>
 * ({@link EffectApprovalRegistry}); the broader principal/link-consent machinery follows.
 */
@Configuration
public class AuthzConfig {

    @Bean
    public EffectApprovalRegistry effectApprovalRegistry(
            @Value("${valem.authz.inherited-effects:approve}") String mode,
            ModelService modelService) {
        EffectApprovalRegistry.Mode m = switch (mode.toLowerCase(Locale.ROOT)) {
            case "allow" -> EffectApprovalRegistry.Mode.ALLOW;
            case "deny"  -> EffectApprovalRegistry.Mode.DENY;
            default       -> EffectApprovalRegistry.Mode.APPROVE;
        };
        return new EffectApprovalRegistry(m, modelService);
    }
}
