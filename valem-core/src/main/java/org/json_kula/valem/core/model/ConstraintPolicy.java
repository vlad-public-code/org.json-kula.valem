package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ConstraintPolicy {
    @JsonProperty("rollback") ROLLBACK,
    @JsonProperty("flag")     FLAG
}

