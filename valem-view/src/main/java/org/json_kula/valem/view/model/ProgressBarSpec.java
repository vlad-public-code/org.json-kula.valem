package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code progressBar} — renders a bound number as a filled bar over a {@code min..max} range. */
public record ProgressBarSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        Double min,
        Double max,
        Boolean showValue,
        String format,
        String tooltip
) implements ComponentSpec {
    public ProgressBarSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
