package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code validationSummary} — the flagged constraints, in one block, with a way to reach the
 * field each one is about.
 *
 * <p>A {@code rollback} constraint rejects the mutation and surfaces as an error on the call. A
 * {@code flag} constraint does not: it records a violation and lets the commit through, which is
 * the point — the model stays editable while it is temporarily inconsistent. But that leaves the
 * violation with nowhere to appear except beside whichever field happens to be bound to the same
 * path, and a constraint spanning three fields is beside none of them.
 *
 * <p>Like {@link TracePanelSpec} this is a declaration, not a computed list: the evaluator has
 * the merged document but not the runtime's flagged-constraint set, so the renderer fills it from
 * the violations it already receives. {@code pathPrefix} narrows it to one section of a long
 * form; unset means every violation in the model.
 */
public record ValidationSummarySpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        String pathPrefix,
        String variant,
        Integer maxItems,
        String emptyText
) implements ComponentSpec {
    public ValidationSummarySpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
