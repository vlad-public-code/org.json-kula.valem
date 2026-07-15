package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ChartSeriesSpec(
        String field,
        String label,
        String color
) {
    @JsonCreator
    public static ChartSeriesSpec of(
            @JsonProperty("field") String field,
            @JsonProperty("label") String label,
            @JsonProperty("color") String color) {
        return new ChartSeriesSpec(field, label, color);
    }
}
