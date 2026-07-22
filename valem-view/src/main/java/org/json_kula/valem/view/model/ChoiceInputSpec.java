package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Covers selectField, radioField, multiSelectField, autocompleteField, comboBox and tagsField —
 * inputs that choose from a list.
 *
 * <p>Only the static {@code options} list is resolved server-side; {@code optionsExpr},
 * {@code optionsUrl} and {@code optionsPath} are declared here because they are part of the
 * component contract, but they are resolved entirely by the client renderer.
 *
 * <p>The three added spellings are the same shape with a different affordance:
 * {@code autocompleteField} and {@code comboBox} filter the option list as you type (what a
 * {@code selectField} degrades into past a few dozen options), and {@code tagsField} writes an
 * array of the chosen values. {@code allowCustom} lets a value outside {@code options} through —
 * the default for {@code tagsField} and {@code comboBox}, never for {@code selectField}. It is a
 * renderer affordance, not a validation rule: the schema still decides what the model accepts.
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
        Boolean allowCustom,
        EventHandler onChange,
        EventHandler onOpen,
        EventHandler onClose
) implements ComponentSpec {
    public ChoiceInputSpec {
        ComponentSpec.requireIdentity(id, type);
        options = options != null ? List.copyOf(options) : null;
    }
}
