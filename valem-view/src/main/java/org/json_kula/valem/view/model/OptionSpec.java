package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record OptionSpec(
        String value,
        String label
) {
    @JsonCreator
    public static OptionSpec of(
            @JsonProperty("value") String value,
            @JsonProperty("label") String label) {
        return new OptionSpec(value, label);
    }
}
