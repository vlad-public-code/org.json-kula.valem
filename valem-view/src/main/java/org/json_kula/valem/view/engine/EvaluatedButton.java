package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.json_kula.valem.view.model.EventHandler;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedButton(
        String id,
        String type,
        String label,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean enabled,
        String variant,
        String icon,
        EventHandler onClick
) implements EvaluatedComponent {}
