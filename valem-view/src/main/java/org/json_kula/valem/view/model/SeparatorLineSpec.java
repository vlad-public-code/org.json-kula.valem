package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code separatorLine} — a horizontal rule. Carries {@code bind} only so its visibility can
 * still inherit from a field's {@code #relevant} metaDerivation.
 */
public record SeparatorLineSpec(
        String id,
        String type,
        JsonNode visible,
        String bind
) implements ComponentSpec {
    public SeparatorLineSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
