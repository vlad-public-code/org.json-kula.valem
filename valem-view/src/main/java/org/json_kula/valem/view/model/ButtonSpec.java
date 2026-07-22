package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code button} — fires {@code onClick} mutations and/or navigates to another view. */
public record ButtonSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        JsonNode enabled,
        String bind,
        String variant,
        String icon,
        EventHandler onClick
) implements ComponentSpec {
    public ButtonSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
