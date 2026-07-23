package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.view.model.EventHandler;

/**
 * Covers: effectStatus.
 *
 * <p>Fully resolved, unlike the other diagnostic components: {@code value} is the effect's status
 * read straight from {@code bind} (its {@code statusPath}) in the merged document, and
 * {@code error} is the message at {@code errorPath} when the effect failed. Both are ordinary
 * model fields, so they arrive in the {@code viewDelta} of the fold-back mutation that set them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedEffectStatus(
        String id,
        String type,
        String label,
        String bind,
        JsonNode value,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String effectId,
        String errorPath,
        String error,
        Boolean showRetry,
        String retryLabel,
        String tooltip,
        EventHandler onRetry
) implements EvaluatedComponent {}
