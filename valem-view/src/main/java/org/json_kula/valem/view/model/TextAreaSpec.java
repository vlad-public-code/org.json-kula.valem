package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code textAreaField} — a basic input plus a visible row count. */
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
        EventHandler onChange
) implements ComponentSpec {
    public TextAreaSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
