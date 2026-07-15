package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DerivationSpec(
        String path,
        String expr,
        EvaluationMode evaluation,
        String description
) {
    @JsonCreator
    public static DerivationSpec of(
            @JsonProperty(value = "path", required = true)  String path,
            @JsonProperty(value = "expr", required = true)  String expr,
            @JsonProperty("evaluation")                      EvaluationMode evaluation,
            @JsonProperty("description")                     String description
    ) {
        return new DerivationSpec(path, expr,
                evaluation != null ? evaluation : EvaluationMode.EAGER,
                description);
    }
}

