package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Covers: sourceList.
 *
 * <p>Citations are authored literals, so a non-browser consumer of {@code GET /models/{id}/view}
 * gets the model's sources — the official authorities its formula is checked against — without
 * evaluating anything.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedSourceList(
        String id,
        String type,
        String label,
        String bind,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        List<EvaluatedSourceItem> sourceItems,
        String tooltip
) implements EvaluatedComponent {}
