package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code sliderField}, {@code ratingField} and {@code numericStepper} — a numeric input
 * constrained to a {@code min..max} range in {@code step} increments.
 *
 * <p>One record, three affordances over the same three numbers: a rating is a slider with
 * {@code min:1, max:5, step:1} drawn as stars, a stepper is one drawn as -/+ buttons. Keeping
 * them here rather than inventing a {@code stars} field means a spec can switch presentation
 * without the bound value or its constraints changing.
 */
public record SliderSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        JsonNode enabled,
        JsonNode readOnly,
        JsonNode required,
        String bind,
        String helperText,
        String tooltip,
        Double min,
        Double max,
        Double step,
        EventHandler onChange
) implements ComponentSpec {
    public SliderSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
