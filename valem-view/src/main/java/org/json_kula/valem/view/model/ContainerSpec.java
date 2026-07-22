package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Covers the plain containers: {@code group}, {@code fieldSet} and {@code sectionItem}.
 *
 * <p>{@code legend} applies to {@code fieldSet}; {@code bind} applies to {@code sectionItem},
 * which edits a single array element ({@code $.array[index]}).
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
        List<ComponentSpec> components
) implements ComponentSpec {
    public ContainerSpec {
        ComponentSpec.requireIdentity(id, type);
        components = components != null ? List.copyOf(components) : null;
    }
}
