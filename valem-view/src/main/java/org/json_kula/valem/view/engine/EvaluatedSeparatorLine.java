package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Covers: separatorLine, spacer. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedSeparatorLine(
        String id,
        String type,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        Integer size
) implements EvaluatedComponent {}
