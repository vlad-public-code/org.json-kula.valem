package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedStaticText(
        String id,
        String type,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String text,
        String format
) implements EvaluatedComponent {}
