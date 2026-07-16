package org.json_kula.valem.api.websocket;

import org.json_kula.valem.api.dto.DispatchedEffect;
import org.json_kula.valem.core.engine.ConstraintEvaluator;

import java.util.List;

/**
 * JSON-serialisable payload pushed to WebSocket subscribers after each committed mutation.
 *
 * <p>{@code kind} discriminates this event from other view-model events on the same topic (currently
 * only {@link SpecEvolvedEvent}); it defaults to {@code "mutation"} via the compact constructor below
 * so every existing caller (which builds a {@code ChangeEvent} without knowing about the discriminator)
 * keeps working unchanged, and every existing subscriber keeps seeing mutation frames identically.
 *
 * @param kind               discriminator; always {@code "mutation"} for this event type
 * @param modelId            the model whose state changed
 * @param mutatedPaths       base fields that were directly written
 * @param derivedUpdated     derived field paths that were re-evaluated
 * @param flaggedConstraints any FLAG/WARN constraint violations
 * @param dispatchedEffects  caller-executed effects that fired (server effects fold back separately)
 */
public record ChangeEvent(
        String kind,
        String modelId,
        List<String> mutatedPaths,
        List<String> derivedUpdated,
        List<ConstraintEvaluator.Violation> flaggedConstraints,
        List<DispatchedEffect> dispatchedEffects
) {
    public ChangeEvent(String modelId,
                       List<String> mutatedPaths,
                       List<String> derivedUpdated,
                       List<ConstraintEvaluator.Violation> flaggedConstraints,
                       List<DispatchedEffect> dispatchedEffects) {
        this("mutation", modelId, mutatedPaths, derivedUpdated, flaggedConstraints, dispatchedEffects);
    }
}
