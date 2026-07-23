package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code textAreaField} and {@code richTextField} — a basic input plus a visible row count.
 *
 * <p>{@code richTextField} stores markdown in the bound string field, so the value stays
 * plain text every other part of the model can read; {@code toolbar} ({@code basic} |
 * {@code full} | {@code none}) only selects how much of an editing affordance the renderer
 * puts above it.
 */
public record TextAreaSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        JsonNode enabled,
        JsonNode readOnly,
        JsonNode required,
        String bind,
        String placeholder,
        String helperText,
        String tooltip,
        Integer rows,
        String toolbar,
        EventHandler onChange
) implements ComponentSpec {
    public TextAreaSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
