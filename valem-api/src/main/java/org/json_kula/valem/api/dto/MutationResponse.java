package org.json_kula.valem.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.DerivationTrace;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.view.engine.EvaluatedComponent;

import java.util.List;
import java.util.Map;

public record MutationResponse(
        boolean success,
        List<String> mutatedPaths,
        List<String> derivedUpdated,
        List<ConstraintEvaluator.Violation> flaggedConstraints,
        List<DispatchedEffect> dispatchedEffects,
        List<DerivationTrace> traces,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<String, EvaluatedComponent> viewDelta
) {
    public static MutationResponse from(ModelRuntime.MutationResult r) {
        return from(r, null);
    }

    public static MutationResponse from(ModelRuntime.MutationResult r, Map<String, EvaluatedComponent> delta) {
        return new MutationResponse(
                r.success(),
                r.mutatedPaths(),
                r.derivedUpdated(),
                r.flaggedConstraints(),
                DispatchedEffect.callerEffects(r),
                r.traces(),
                delta);
    }
}
