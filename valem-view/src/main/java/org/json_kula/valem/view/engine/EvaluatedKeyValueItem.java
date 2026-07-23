package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One resolved row of an {@link EvaluatedKeyValueList}: the caption, the path it came from, and
 * the value read out of the merged document (or the evaluated {@code text} when the row had no
 * {@code bind}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedKeyValueItem(
        String label,
        String bind,
        JsonNode value,
        String text,
        String format,
        String currency
) {}
