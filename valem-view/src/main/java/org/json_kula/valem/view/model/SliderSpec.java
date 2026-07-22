package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code sliderField} — a numeric input constrained to a range. */
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
