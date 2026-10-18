package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * An evaluated {@code editableTable}. As with {@code sectionList}, cell components of an
 * array-bound table are evaluated once, as templates that still carry {@code [*]}; {@code value} is
 * the bound array, so a consumer knows how many rows to draw.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedEditableTable(
        String id,
        String type,
        String label,
        String bind,
        JsonNode value,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        EvaluatedGridColumn rowHeader,
        List<EvaluatedGridColumn> gridColumns,
        List<EvaluatedGridRow> gridRows,
        boolean canAdd,
        boolean canRemove,
        String addLabel,
        String removeLabel,
        String emptyText,
        String tooltip
) implements EvaluatedComponent {}
