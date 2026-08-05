package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code sourceList} — a titled block of external source citations.
 *
 * <p>The E-E-A-T requirement made a first-class component: a calculator that asserts a formula
 * should say where the formula comes from, and cite it with a dated link to the official authority.
 * Authoring the same thing out of {@code link} components inside a {@code group} loses the shared
 * heading and the "checked on" dates, and repeats the same boilerplate on every page.
 *
 * <p>Rows come from {@code items}; {@code label} is the heading (defaults to "Sources" in the
 * renderer). {@code bind} is optional and only the meta-inheritance anchor, never the row source —
 * a source block cites authorities, it does not read model state.
 */
public record SourceListSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        List<SourceItemSpec> items,
        String tooltip
) implements ComponentSpec {
    public SourceListSpec {
        ComponentSpec.requireIdentity(id, type);
        items = items != null ? List.copyOf(items) : null;
    }
}
