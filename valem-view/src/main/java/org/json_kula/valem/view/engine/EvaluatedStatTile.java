package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Covers: statTile, metric.
 *
 * <p>{@code value} is the bound number when {@code bind} is set, and otherwise the evaluated
 * {@code value} expression; {@code delta}, {@code caption} and {@code trend} arrive as resolved
 * strings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedStatTile(
        String id,
        String type,
        String label,
        String bind,
        JsonNode value,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String delta,
        String caption,
        String trend,
        String format,
        String currency,
        String variant,
        String icon,
        String tooltip
) implements EvaluatedComponent {}
