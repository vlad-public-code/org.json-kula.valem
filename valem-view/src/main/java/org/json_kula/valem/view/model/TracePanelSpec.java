package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code explainPanel} and {@code auditTimeline} — the two bounded, path-scoped record trails
 * Valem already keeps, surfaced in a view.
 *
 * <p>{@code explainPanel} shows why a field holds its current value ({@code GET
 * /models/{id}/explain/{path}}: the live derivation and constraint traces from the runtime's ring
 * buffer). {@code auditTimeline} shows how it got there ({@code GET /models/{id}/audit}: the
 * durable, hash-chained record of committed cycles). Same shape — a path, a limit, a list — so
 * one record covers both.
 *
 * <p><strong>The server does not fill these in.</strong> {@code ViewEvaluator} is handed only the
 * merged document, the meta cache, the expression cache and the constants; it has no access to
 * the trace log or the audit store, and giving it one would put an unbounded read inside every
 * view evaluation and every {@code viewDelta}. So the evaluated form carries the declaration —
 * which path, how many rows — and the renderer fetches the rows itself, the same division of
 * labour as {@code optionsUrl}. A consumer of the raw {@code GET /models/{id}/view} response
 * gets the declaration, not the trace.
 *
 * <p>{@code bind} is the path being explained; for {@code auditTimeline} it is a path
 * <em>prefix</em>, and leaving it unset means the whole model.
 */
public record TracePanelSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        Integer limit,
        Boolean showConstraints,
        JsonNode collapsed,
        String tooltip
) implements ComponentSpec {
    public TracePanelSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
