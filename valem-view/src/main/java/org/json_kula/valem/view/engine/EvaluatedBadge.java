package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedBadge(
        String id,
        String type,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String variant,
        String text,
        String label
) implements EvaluatedComponent {}
