package org.json_kula.valem.api.websocket;

import org.json_kula.valem.api.dto.DispatchedEffect;
import org.json_kula.valem.core.engine.ConstraintEvaluator;

import java.util.List;

/**
 * JSON-serialisable payload pushed to WebSocket subscribers after each committed mutation.
 *
 * @param modelId            the model whose state changed
 * @param mutatedPaths       base fields that were directly written
 * @param derivedUpdated     derived field paths that were re-evaluated
 * @param flaggedConstraints any FLAG/WARN constraint violations
 * @param dispatchedEffects  caller-executed effects that fired (server effects fold back separately)
 */
public record ChangeEvent(
        String modelId,
        List<String> mutatedPaths,
        List<String> derivedUpdated,
        List<ConstraintEvaluator.Violation> flaggedConstraints,
        List<DispatchedEffect> dispatchedEffects
) {}
