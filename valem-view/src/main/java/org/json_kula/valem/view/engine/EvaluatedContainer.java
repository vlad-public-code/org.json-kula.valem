package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Covers {@code group} and {@code fieldSet} — containers of sub-components. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedContainer(
        String id,
        String type,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String layout,
        Integer columns,
        List<EvaluatedComponent> components,
        String legend
) implements EvaluatedComponent {}
