package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Covers: explainPanel, auditTimeline.
 *
 * <p>A declaration, not a result. The evaluator has no access to the trace ring buffer or the
 * audit store — see {@code TracePanelSpec} — so what travels here is which path to explain and
 * how many rows to ask for; the renderer calls {@code /explain/{path}} or {@code /audit} itself.
 * {@code visible} and {@code collapsed} are resolved, so a panel can still be driven by state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedTracePanel(
        String id,
        String type,
        String label,
        String bind,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        Integer limit,
        Boolean showConstraints,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean collapsed,
        String tooltip
) implements EvaluatedComponent {}
