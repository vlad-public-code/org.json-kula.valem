package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.view.model.ColumnSpec;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedDataTable(
        String id,
        String type,
        String label,
        String bind,
        JsonNode value,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        List<ColumnSpec> tableColumns,
        Integer pageSize,
        String tooltip
) implements EvaluatedComponent {}
