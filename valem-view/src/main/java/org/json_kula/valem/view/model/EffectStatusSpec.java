package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code effectStatus} — an effect's {@code statusPath} state machine, drawn.
 *
 * <p>{@code bind} is the effect's {@code statusPath}, so the component reads
 * {@code pending → in_flight → applied | failed} straight out of the merged document like any
 * other bound value: no new server contract, and the status updates through the same
 * {@code viewDelta} as everything else. That is the whole point of effects folding their state
 * back into the model rather than living beside it.
 *
 * <p>Without this component the only way to show an in-flight effect is a {@code badge} with a
 * JSONata {@code variant}, which the server passes through unevaluated — so it renders correctly
 * in the bundled UI and nowhere else. Here the mapping from status to severity is the renderer's,
 * and every consumer gets it.
 *
 * <p>{@code onRetry} is an ordinary mutation handler; the natural body clears {@code statusPath}
 * back to {@code pending}, which re-arms the dedupe guard and re-fires the effect. The component
 * does not re-dispatch anything itself — a view cannot execute an effect, only ask the model to.
 */
public record EffectStatusSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        String effectId,
        String errorPath,
        Boolean showRetry,
        String retryLabel,
        String tooltip,
        EventHandler onRetry
) implements ComponentSpec {
    public EffectStatusSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
