package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code link} — an anchor to somewhere outside the model.
 *
 * <p>{@code href} and {@code text} are JSONata-capable so a link can be built from state
 * ({@code "'https://tracking.example/' & shipment.trackingId"}). Navigation <em>inside</em> the
 * model is not this component: use a {@code button} or {@code menu} with a {@code navigate}
 * handler, which switches view without leaving the page or losing unsaved edits.
 *
 * <p>{@code target} is passed through to the renderer; it pairs an external {@code _blank} with
 * {@code rel="noopener noreferrer"} rather than trusting the spec to remember to.
 */
public record LinkSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        JsonNode href,
        JsonNode text,
        String target,
        String icon
) implements ComponentSpec {
    public LinkSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
