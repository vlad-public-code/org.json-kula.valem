package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.view.model.ChartSeriesSpec;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedDataChart(
        String id,
        String type,
        String label,
        String bind,
        JsonNode value,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String chartType,
        String chartX,
        List<ChartSeriesSpec> chartSeries
) implements EvaluatedComponent {}
