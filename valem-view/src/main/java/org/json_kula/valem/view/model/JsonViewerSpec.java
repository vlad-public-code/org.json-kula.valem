package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code jsonViewer} — the bound subtree as formatted JSON.
 *
 * <p>Unlike the other diagnostic components this one <em>is</em> resolved server-side, because
 * its data is exactly the bound value the evaluator already reads. Binding it to {@code $} shows
 * the whole merged document — base fields with the derived values spliced in — which is the view
 * of a model an author actually wants while writing derivations.
 *
 * <p>{@code maxDepth} truncates deeper nodes to {@code …} so a large model does not paint a
 * screen of noise; {@code collapsed} is the initial fold state, as on {@link ContainerSpec}.
 */
public record JsonViewerSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        JsonNode collapsed,
        Integer maxDepth,
        String tooltip
) implements ComponentSpec {
    public JsonViewerSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
