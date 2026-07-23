package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Covers: validationSummary. Like {@link EvaluatedTracePanel} this carries the declaration — the
 * violations themselves reach the renderer through its own violations map, not through the view.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedValidationSummary(
        String id,
        String type,
        String label,
        String bind,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String pathPrefix,
        String variant,
        Integer maxItems,
        String emptyText
) implements EvaluatedComponent {}
