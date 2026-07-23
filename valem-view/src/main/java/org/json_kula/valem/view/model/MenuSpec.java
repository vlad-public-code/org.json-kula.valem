package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code menu}, {@code stepper} and {@code breadcrumb} — view navigation over a list of
 * {@code menuItems}, each naming a {@code targetView}.
 *
 * <p>A stepper shows the same items as numbered steps and a breadcrumb as a trail, which is why
 * they share a record: all three navigate, none of them holds a position of its own. The step a
 * wizard is "on" is the active view id, so it survives a reload and can be driven by a
 * {@code button}'s {@code navigate} handler like any other view change.
 */
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
