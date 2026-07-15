package org.json_kula.valem.api.effects;

import org.json_kula.valem.core.engine.EffectRequest;

/**
 * Shell-side SPI for a <b>plugin effect kind</b> — the impure half of a custom effect. An implementation
 * performs the actual I/O for an {@link EffectRequest.Plugin} whose {@code kind} matches {@link #kind()}
 * and folds any {@code responseSet} back through {@link org.json_kula.valem.service.ModelService}
 * (extend {@link EffectShell} to reuse {@code setPhase}/{@code applyFoldback}/{@code evalResponseSet}).
 *
 * <p>Implementations are collected as Spring beans (any {@code EffectExecutor} bean on the context is
 * picked up), so a plugin ships its executor as a bean — typically via a Spring Boot auto-configuration
 * so that merely putting the jar on the classpath registers it. Getting {@code ModelService} injected is
 * exactly why executors are beans rather than {@code ServiceLoader}-discovered.
 *
 * <p>The four built-in kinds ({@code server}/{@code caller}/{@code llm}/{@code timer}) are routed
 * directly by {@link CompositeEffectExecutor} and do not implement this interface.
 */
public interface EffectExecutor {

    /** The plugin kind this executor handles — must equal the {@link EffectRequest.Plugin#kind()} value. */
    String kind();

    /** Performs the effect's I/O and folds any result back. Runs post-commit, off the mutation thread. */
    void submit(String modelId, EffectRequest.Plugin request);
}
