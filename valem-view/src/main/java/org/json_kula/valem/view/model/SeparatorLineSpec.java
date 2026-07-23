package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code separatorLine} — a horizontal rule — and {@code spacer}, the same thing without the
 * rule. Carries {@code bind} only so its visibility can still inherit from a field's
 * {@code #relevant} metaDerivation: a divider that stays behind after the group it divided is
 * hidden is the most common cosmetic bug in a conditional form.
 *
 * <p>{@code size} is the gap in pixels a {@code spacer} occupies (default 16); a
 * {@code separatorLine} ignores it.
 */
public record SeparatorLineSpec(
        String id,
        String type,
        JsonNode visible,
        String bind,
        Integer size
) implements ComponentSpec {
    public SeparatorLineSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
