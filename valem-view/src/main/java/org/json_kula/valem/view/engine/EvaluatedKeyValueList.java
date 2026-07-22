package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Covers: keyValueList, summaryList.
 *
 * <p>Rows are fully resolved server-side, so a non-browser consumer of {@code GET
 * /models/{id}/view} gets a printable summary of the model without evaluating anything.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedKeyValueList(
        String id,
        String type,
        String label,
        String bind,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        List<EvaluatedKeyValueItem> items,
        Integer columns,
        String tooltip
) implements EvaluatedComponent {}
