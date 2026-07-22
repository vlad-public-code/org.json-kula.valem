package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code badge} — a status indicator — plus {@code alert} and {@code callout}, the block-level
 * spellings of the same three fields: {@code label} is the heading, {@code text} the body,
 * {@code variant} the severity.
 *
 * <p>{@code variant} is a plain string, deliberately not a JSONata-capable {@link JsonNode}:
 * the evaluator passes it through unevaluated. Only the bundled React renderer re-evaluates a
 * JSONata {@code variant} client-side.
 */
public record BadgeSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        JsonNode text,
        String variant
) implements ComponentSpec {
    public BadgeSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
