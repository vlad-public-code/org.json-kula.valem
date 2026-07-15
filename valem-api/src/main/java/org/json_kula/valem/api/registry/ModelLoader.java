package org.json_kula.valem.api.registry;

import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.persistence.ModelStore;
import org.json_kula.valem.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Restores all persisted model instances at application startup via {@link ModelService#loadModel}.
 * If the {@link ModelStore} is not enabled (in-memory no-op), returns immediately.
 *
 * <p><b>Degrade, don't drop (F-T5).</b> A failure to reconstruct a model's <em>state</em> (a corrupt
 * snapshot or mutation log) must not make the whole model disappear: the spec is still loaded with an
 * empty state ({@code derivedCache} is recomputed on first access), so the model stays registered and
 * queryable instead of silently vanishing. Only a missing/unreadable spec, or a failure to register
 * the model at all, causes it to be skipped — there is nothing to serve without the spec.
 */
@Component
public class ModelLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelLoader.class);

    private final ModelStore   store;
    private final ModelService service;

    public ModelLoader(ModelStore store, ModelService service) {
        this.store   = store;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!store.isEnabled()) return;

        int loaded   = 0;
        int degraded = 0;
        int failed   = 0;

        try {
            for (String modelId : store.modelIds()) {
                try {
                    Optional<ModelSpec> maybeSpec = store.loadSpec(modelId);
                    if (maybeSpec.isEmpty()) {
                        log.warn("No spec found for persisted model '{}' — skipping", modelId);
                        failed++;
                        continue;
                    }

                    // A state-load failure degrades to spec-only rather than dropping the model.
                    Optional<Snapshot> maybeSnap;
                    boolean stateDegraded = false;
                    try {
                        maybeSnap = store.loadSnapshot(modelId);
                    } catch (Exception e) {
                        log.error("Failed to load persisted state for model '{}' — loading spec only "
                                + "(state reset; derivations recompute on first access)", modelId, e);
                        maybeSnap = Optional.empty();
                        stateDegraded = true;
                    }

                    service.loadModel(maybeSpec.get(), maybeSnap);
                    if (stateDegraded) {
                        degraded++;
                    } else {
                        if (maybeSnap.isPresent()) log.debug("Restored snapshot for model '{}'", modelId);
                        log.info("Loaded persisted model '{}'", modelId);
                        loaded++;
                    }
                } catch (Exception e) {
                    log.error("Failed to load persisted model '{}' — skipping", modelId, e);
                    failed++;
                }
            }
        } catch (Exception e) {
            log.error("Failed to list persisted models", e);
        }

        if (loaded > 0 || degraded > 0 || failed > 0) {
            log.info("Model persistence: {} loaded, {} degraded (spec-only), {} failed",
                    loaded, degraded, failed);
        }
    }
}
