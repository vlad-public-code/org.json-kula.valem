package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One row of a {@code keyValueList}: a caption and the value beside it.
 *
 * <p>{@code bind} reads a path out of the merged document — which is what makes this the natural
 * summary of a derivation-heavy model, since a derived field is already in there. {@code text} is
 * the escape hatch for a row that has no single path behind it (a JSONata expression, evaluated
 * like any other {@code text}); when both are set {@code bind} wins, so a row never shows two
 * different things depending on which field the renderer happened to read.
 *
 * <p>{@code currency} is per row rather than per list because a summary legitimately mixes them —
 * a quoted price beside its converted equivalent. A {@code format} of {@code currency} with no
 * code renders in the renderer's default, which is why a money row should always name one.
 */
public record KeyValueItemSpec(
        String label,
        String bind,
        JsonNode text,
        String format,
        String currency
) {
    @JsonCreator
    public static KeyValueItemSpec of(
            @JsonProperty("label")    String label,
            @JsonProperty("bind")     String bind,
            @JsonProperty("text")     JsonNode text,
            @JsonProperty("format")   String format,
            @JsonProperty("currency") String currency) {
        return new KeyValueItemSpec(label, bind, text, format, currency);
    }
}
