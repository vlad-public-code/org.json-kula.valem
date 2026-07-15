package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * A spec-embedded test case. Values in {@code expect} may be plain JSON or
 * {@code {"$meta": {"required": true, "minimum": 0}}} assertions.
 */
public record TestCase(
        String description,
        Map<String, JsonNode> given,
        Map<String, JsonNode> expect
) {
    @JsonCreator
    public static TestCase of(
            @JsonProperty("description")                     String description,
            @JsonProperty(value = "given",  required = true) Map<String, JsonNode> given,
            @JsonProperty(value = "expect", required = true) Map<String, JsonNode> expect
    ) {
        return new TestCase(description, Map.copyOf(given), Map.copyOf(expect));
    }

    /** Returns true if this expect entry is a $meta assertion rather than a value assertion. */
    public static boolean isMetaAssertion(JsonNode expectValue) {
        return expectValue.isObject() && expectValue.has("$meta");
    }
}

