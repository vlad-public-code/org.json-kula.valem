package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code progressBar} — renders a bound number as a filled bar over a {@code min..max} range —
 * and {@code gauge}, the same value drawn as an arc.
 *
 * <p>{@code helperText} is part of the authored format (the React renderer shows it) but is not
 * carried on {@code EvaluatedProgressBar}, so the server does not surface it.
 */
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
        String helperText,
        String tooltip
) implements ComponentSpec {
    public ProgressBarSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
