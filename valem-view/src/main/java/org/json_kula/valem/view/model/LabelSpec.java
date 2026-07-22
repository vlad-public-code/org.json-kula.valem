package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code label} — displays a bound value, or {@code text} when no bind is given. */
public record LabelSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        JsonNode text
) implements ComponentSpec {
    public LabelSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
