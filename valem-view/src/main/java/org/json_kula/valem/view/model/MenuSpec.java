package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** {@code menu} — view navigation. */
public record MenuSpec(
        String id,
        String type,
        JsonNode visible,
        String bind,
        String orientation,
        List<MenuItemSpec> menuItems
) implements ComponentSpec {
    public MenuSpec {
        ComponentSpec.requireIdentity(id, type);
        menuItems = menuItems != null ? List.copyOf(menuItems) : null;
    }
}
