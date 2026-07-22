package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code staticText} — a markdown/HTML literal, or a JSONata expression producing one. */
public record StaticTextSpec(
        String id,
        String type,
        JsonNode visible,
        String bind,
        JsonNode text
) implements ComponentSpec {
    public StaticTextSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
