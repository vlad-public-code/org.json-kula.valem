package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code keyValueList} / {@code summaryList} — a read-only caption/value list.
 *
 * <p>The review step of a form, and the shape a Valem model most often wants to end on: every
 * interesting number in a spec is already a derivation, so summarising one is a list of paths,
 * not a layout of components. Authoring the same thing out of {@code label} components inside a
 * {@code group} costs two components per row and loses the alignment.
 *
 * <p>Rows come from {@code items}. {@code bind} is optional and does not supply them — it is the
 * meta-inheritance anchor, so a summary block can inherit {@code #relevant} from the section it
 * summarises.
 */
public record KeyValueListSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        List<KeyValueItemSpec> items,
        Integer columns,
        String tooltip
) implements ComponentSpec {
    public KeyValueListSpec {
        ComponentSpec.requireIdentity(id, type);
        items = items != null ? List.copyOf(items) : null;
    }
}
