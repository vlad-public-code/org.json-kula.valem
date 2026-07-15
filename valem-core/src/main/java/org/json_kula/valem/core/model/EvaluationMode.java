package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum EvaluationMode {
    @JsonProperty("eager") EAGER,
    @JsonProperty("lazy")  LAZY
}

