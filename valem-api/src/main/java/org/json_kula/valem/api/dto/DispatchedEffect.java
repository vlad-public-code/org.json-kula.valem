package org.json_kula.valem.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.engine.ModelRuntime;

import java.util.List;

/**
 * A caller-executed ({@code executor: caller}) effect surfaced in the mutation response for the client
 * to act on — the successor to the removed {@code actions} feature. Server effects are executed
 * asynchronously and fold back later, so they are not part of the synchronous response.
 */
public record DispatchedEffect(String effectId, String emit, JsonNode payload) {

    public static DispatchedEffect from(EffectRequest.Caller c) {
        return new DispatchedEffect(c.effectId(), c.emit(), c.payload());
    }

    /** Extracts the caller effects from a mutation result; server effects are excluded (async). */
    public static List<DispatchedEffect> callerEffects(ModelRuntime.MutationResult r) {
        return r.dispatchedEffects().stream()
                .filter(e -> e instanceof EffectRequest.Caller)
                .map(e -> from((EffectRequest.Caller) e))
                .toList();
    }
}
