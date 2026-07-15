package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ColumnSpec(
        String field,
        String header,
        String format,
        String width
) {
    @JsonCreator
    public static ColumnSpec of(
            @JsonProperty("field")  String field,
            @JsonProperty("header") String header,
            @JsonProperty("format") String format,
            @JsonProperty("width")  String width) {
        return new ColumnSpec(field, header, format, width);
    }
}
