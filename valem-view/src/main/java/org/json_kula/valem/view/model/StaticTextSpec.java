package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code staticText} — a block of text, or a JSONata expression producing one.
 *
 * <p>{@code format} selects how it is rendered: {@code markdown} (the default — escapes the
 * source, then applies light formatting), {@code text} (escaped, verbatim), or {@code html}
 * (unescaped). The default is safe because a {@code staticText} is often bound to model state and
 * Valem has no per-field access control, so {@code html} — which injects its content unescaped —
 * must be opted into for authored, trusted content only.
 */
public record StaticTextSpec(
        String id,
        String type,
        JsonNode visible,
        String bind,
        JsonNode text,
        String format
) implements ComponentSpec {
    public StaticTextSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
