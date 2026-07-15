package org.json_kula.valem.core.engine.spi;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.model.EffectSpec;

/**
 * SPI for a <b>pluggable effect kind</b> — the pure half of a custom effect. An implementation declares
 * a {@link #kind()} string (the {@code executor} value a spec selects), validates the spec fragment, and
 * resolves it into an {@link EffectRequest.Plugin} carrying already-evaluated data. It performs
 * <b>no I/O</b>; the matching shell-side {@code EffectExecutor} (registered for the same {@code kind()})
 * does the I/O and folds any response back.
 *
 * <p>Implementations are discovered at runtime via {@link java.util.ServiceLoader}: a plugin jar ships a
 * {@code META-INF/services/org.json_kula.valem.core.engine.spi.EffectKind} file naming the class.
 * Adding a jar (and enabling the kind, see
 * {@link org.json_kula.valem.core.engine.spi.EffectKindRegistry}) is enough — no core edits.
 *
 * <p>The four built-in kinds ({@code server}, {@code caller}, {@code llm}, {@code timer}) are handled
 * directly by the engine and do <b>not</b> go through this SPI; it exists purely to add new kinds.
 */
public interface EffectKind {

    /** The {@code executor} string this kind handles (e.g. {@code "s3"}). Must be stable and unique. */
    String kind();

    /**
     * Whether this kind has durable, foldable in-flight state (like {@code server}/{@code llm}/
     * {@code timer}): if {@code true} it participates in crash-recovery reconcile and superseded re-fire.
     * Return {@code false} for a fire-and-surface kind with no server-side fold-back (like {@code caller}).
     */
    default boolean durable() { return true; }

    /** Validates this effect's kind-specific fields, recording any problems on {@code ctx}. */
    void validate(EffectSpec effect, String location, EffectValidationContext ctx);

    /**
     * Resolves {@code effect} into a request. Evaluate the effect's expression-bearing fields via
     * {@code ctx} and pack the results into {@link EffectRequest.Plugin#params()}. {@code statusPath} and
     * {@code responseSet} on the returned {@code Plugin} feed the shared fold-back machinery, so populate
     * them from the spec. {@code dedupeKey} is the already-evaluated edge key the effect fired with
     * (may be {@code null}); place it on the returned {@code Plugin} unchanged.
     */
    EffectRequest.Plugin resolve(EffectSpec effect, EffectEvalContext ctx, JsonNode dedupeKey);
}
