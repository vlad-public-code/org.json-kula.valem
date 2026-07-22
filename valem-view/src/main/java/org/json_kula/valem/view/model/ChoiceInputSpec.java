package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Covers selectField, radioField and multiSelectField — inputs that choose from a list.
 *
 * <p>Only the static {@code options} list is resolved server-side; {@code optionsExpr},
 * {@code optionsUrl} and {@code optionsPath} are declared here because they are part of the
 * component contract, but they are resolved entirely by the client renderer.
 */
public record ChoiceInputSpec(
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
        String optionsExpr,
        String optionsUrl,
        String optionsPath,
        EventHandler onChange,
        EventHandler onOpen,
        EventHandler onClose
) implements ComponentSpec {
    public ChoiceInputSpec {
        ComponentSpec.requireIdentity(id, type);
        options = options != null ? List.copyOf(options) : null;
    }
}
