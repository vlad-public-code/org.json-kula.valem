package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One column of a {@code dataTable}.
 *
 * <p>{@code currency} is per column for the same reason it is per row on
 * {@link KeyValueItemSpec}: a table legitimately holds more than one, and a {@code currency}
 * format with no code renders in whatever the renderer defaults to — which is how a euro column
 * ends up displayed as dollars.
 *
 * <p>{@code format} takes the same vocabulary as everywhere else in the catalog
 * ({@code currency} | {@code percent} | {@code number} | {@code integer}), and {@code percent}
 * appends a sign rather than multiplying by 100, so a column and a {@code summaryList} row over
 * the same field always read the same.
 */
public record ColumnSpec(
        String field,
        String header,
        String format,
        String currency,
        String width
) {
    @JsonCreator
    public static ColumnSpec of(
            @JsonProperty("field")    String field,
            @JsonProperty("header")   String header,
            @JsonProperty("format")   String format,
            @JsonProperty("currency") String currency,
            @JsonProperty("width")    String width) {
        return new ColumnSpec(field, header, format, currency, width);
    }
}
