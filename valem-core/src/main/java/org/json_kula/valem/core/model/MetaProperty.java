package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

public enum MetaProperty {
    @JsonProperty("required")   REQUIRED,
    @JsonProperty("minimum")    MINIMUM,
    @JsonProperty("maximum")    MAXIMUM,
    @JsonProperty("minLength")  MIN_LENGTH,
    @JsonProperty("maxLength")  MAX_LENGTH,
    @JsonProperty("pattern")    PATTERN,
    @JsonProperty("enum")       ENUM,
    @JsonProperty("readOnly")    READ_ONLY,
    @JsonProperty("relevant")    RELEVANT,
    @JsonProperty("multipleOf")  MULTIPLE_OF;

    private static final Set<FieldType> ALL   = Set.of(FieldType.values());
    private static final Set<FieldType> NUM   = Set.of(FieldType.NUMBER);
    private static final Set<FieldType> STR   = Set.of(FieldType.STRING);
    private static final Set<FieldType> PRIM  = Set.of(FieldType.NUMBER, FieldType.STRING, FieldType.BOOLEAN);

    /** Field types this meta property is valid for. */
    public Set<FieldType> applicableTo() {
        return switch (this) {
            case REQUIRED, READ_ONLY, RELEVANT   -> ALL;
            case MINIMUM, MAXIMUM, MULTIPLE_OF   -> NUM;
            case MIN_LENGTH, MAX_LENGTH, PATTERN -> STR;
            case ENUM                            -> PRIM;
        };
    }

    /** Returns the corresponding JSON Schema keyword, or null for Valem-only properties. */
    public String jsonSchemaKeyword() {
        return switch (this) {
            case REQUIRED    -> "required";   // handled specially (array on parent object)
            case MINIMUM     -> "minimum";
            case MAXIMUM     -> "maximum";
            case MULTIPLE_OF -> "multipleOf";
            case MIN_LENGTH  -> "minLength";
            case MAX_LENGTH  -> "maxLength";
            case PATTERN     -> "pattern";
            case ENUM        -> "enum";
            case READ_ONLY   -> "readOnly";
            case RELEVANT    -> null;         // Valem-only, not a JSON Schema keyword
        };
    }
}

