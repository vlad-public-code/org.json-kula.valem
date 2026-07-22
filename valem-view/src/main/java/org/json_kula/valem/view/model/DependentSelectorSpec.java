package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code countryRegionSelector} — a choice input whose options depend on another component's
 * value; {@code dependsOn} is the bind path of the driving {@code countrySelector}.
 */
public record DependentSelectorSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        JsonNode enabled,
        JsonNode readOnly,
        JsonNode required,
        String bind,
        String placeholder,
        String helperText,
        String tooltip,
        List<OptionSpec> options,
        String dependsOn,
        EventHandler onChange
) implements ComponentSpec {
    public DependentSelectorSpec {
        ComponentSpec.requireIdentity(id, type);
        options = options != null ? List.copyOf(options) : null;
    }
}
