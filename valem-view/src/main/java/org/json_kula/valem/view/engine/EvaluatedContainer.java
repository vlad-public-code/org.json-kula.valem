package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Covers {@code group}, {@code fieldSet} and {@code sectionItem} — containers of sub-components.
 * {@code bind} is set only by {@code sectionItem}, which edits one array element.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedContainer(
        String id,
        String type,
        String bind,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String layout,
        Integer columns,
        List<EvaluatedComponent> components,
        String legend
) implements EvaluatedComponent {}
