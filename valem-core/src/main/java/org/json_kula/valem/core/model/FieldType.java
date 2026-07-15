package org.json_kula.valem.core.model;

import com.fasterxml.jackson.databind.JsonNode;

public enum FieldType {
    NUMBER, STRING, BOOLEAN, OBJECT, ARRAY, BINARY;

    /** Derives the FieldType from a JSON Schema property node. */
    public static FieldType of(JsonNode schemaNode) {
        // Binary is detected by the Valem extension keyword
        if (schemaNode.path("x-valem-binary").asBoolean(false)) {
            return BINARY;
        }
        String type = schemaNode.path("type").asText("");
        return switch (type) {
            case "number", "integer" -> NUMBER;
            case "boolean"           -> BOOLEAN;
            case "array"             -> ARRAY;
            case "object"            -> OBJECT;
            default                  -> STRING; // "string" or unknown
        };
    }
}

