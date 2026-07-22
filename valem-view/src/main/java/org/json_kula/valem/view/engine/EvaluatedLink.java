package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Covers: link. Both {@code href} and {@code text} arrive already evaluated. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedLink(
        String id,
        String type,
        String label,
        String bind,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String href,
        String text,
        String target,
        String icon
) implements EvaluatedComponent {}
