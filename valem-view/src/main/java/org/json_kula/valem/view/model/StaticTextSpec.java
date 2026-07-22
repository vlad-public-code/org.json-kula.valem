package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code staticText} — a block of text, or a JSONata expression producing one.
 *
 * <p>{@code format} selects how it is rendered: {@code html} (the default, kept so existing specs
 * are unaffected), {@code markdown}, or {@code text}. Only {@code html} injects its content
 * unescaped, so it is the wrong choice for anything a user typed — {@code markdown} is the read
 * half of {@code richTextField} and escapes the source before adding markup, and {@code text}
 * shows the content verbatim.
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
