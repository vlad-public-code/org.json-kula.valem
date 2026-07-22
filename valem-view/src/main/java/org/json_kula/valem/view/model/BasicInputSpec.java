package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Covers the input types that need nothing beyond the common input fields:
 * textField, numericField, passwordField, emailField, phoneNumberField, checkboxField,
 * toggleField, dateField, dateTimeField, timeField, countrySelector.
 */
public record BasicInputSpec(
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
        EventHandler onChange
) implements ComponentSpec {
    public BasicInputSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
