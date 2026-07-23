package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Covers the input types that need nothing beyond the common input fields:
 * textField, numericField, currencyField, percentField, passwordField, emailField,
 * phoneNumberField, checkboxField, toggleField, dateField, dateTimeField, timeField,
 * countrySelector.
 *
 * <p>{@code format} and {@code currency} exist for the numeric spellings: {@code currencyField}
 * and {@code percentField} are {@code numericField} plus a display convention, not a different
 * input. Both are presentation-only — the bound value stays a plain number, so derivations and
 * constraints see the same thing whichever spelling the author picked. {@code currency} is an
 * ISO-4217 code ({@code "EUR"}); {@code format} defaults per type
 * ({@code currency} / {@code percent} / none).
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
        String format,
        String currency,
        EventHandler onChange
) implements ComponentSpec {
    public BasicInputSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
