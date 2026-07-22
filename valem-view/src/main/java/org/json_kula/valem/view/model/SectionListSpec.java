package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code sectionList} — repeats an editor over a bound array, with add/remove controls.
 * {@code itemView} names the view used to edit one element.
 */
public record SectionListSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        String itemView,
        JsonNode canAdd,
        JsonNode canRemove,
        String addLabel,
        String removeLabel,
        String layout,
        Integer columns,
        List<ComponentSpec> components,
        EventHandler onChange
) implements ComponentSpec {
    public SectionListSpec {
        ComponentSpec.requireIdentity(id, type);
        components = components != null ? List.copyOf(components) : null;
    }
}
