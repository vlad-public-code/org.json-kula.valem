package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code statTile} / {@code metric} — one headline number with its supporting text.
 *
 * <p>{@code bind} names the number; {@code delta} and {@code caption} are JSONata expressions
 * evaluated like {@code text}, which is how a tile shows movement ({@code total - $const.budget})
 * without the model having to carry a field for it.
 *
 * <p>{@code trend} ({@code up} | {@code down} | {@code flat} | a JSONata expression producing
 * one) is deliberately separate from the sign of {@code delta}: whether a rising number is good
 * news is a domain question — spend up is bad, savings up is good — and only the spec author
 * knows the answer. Leaving it unset means the renderer shows the delta without a verdict.
 */
public record StatTileSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        JsonNode value,
        JsonNode delta,
        JsonNode caption,
        JsonNode trend,
        String format,
        String currency,
        String variant,
        String icon,
        String tooltip
) implements ComponentSpec {
    public StatTileSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
