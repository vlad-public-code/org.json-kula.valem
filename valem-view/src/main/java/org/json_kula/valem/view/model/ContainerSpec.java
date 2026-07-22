package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Covers the plain containers: {@code group}, {@code fieldSet}, {@code card}, {@code toolbar},
 * {@code buttonGroup}, {@code tabs}, {@code tabItem}, {@code accordion}, {@code collapsible}
 * and {@code sectionItem}.
 *
 * <p>They differ only in chrome, which is why one record covers them: {@code card} is a
 * {@code group} with a titled surface, {@code toolbar} / {@code buttonGroup} lay their children
 * out in a row, and {@code tabs} / {@code accordion} give each child its own panel. Nothing here
 * changes how children are evaluated — a container never owns state, so switching a {@code group}
 * to a {@code card} cannot alter what the view computes.
 *
 * <p>{@code legend} applies to {@code fieldSet}; {@code bind} applies to {@code sectionItem},
 * which edits a single array element ({@code $.array[index]}).
 *
 * <p>{@code collapsed} is the <em>initial</em> state of a {@code collapsible} (and of each
 * {@code tabItem} nested in an {@code accordion}), not a live binding: it is resolved once per
 * evaluation like any other dynamic field, and the renderer owns the open/closed state from then
 * on. Expressing "this section starts closed until the user picks a plan" is therefore fine;
 * expressing "force this section closed whenever X" is not — use {@code visible} for that.
 */
public record ContainerSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        String layout,
        Integer columns,
        String legend,
        JsonNode collapsed,
        List<ComponentSpec> components
) implements ComponentSpec {
    public ContainerSpec {
        ComponentSpec.requireIdentity(id, type);
        components = components != null ? List.copyOf(components) : null;
    }
}
