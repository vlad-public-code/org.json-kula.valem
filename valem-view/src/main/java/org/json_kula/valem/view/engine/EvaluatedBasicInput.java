package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.view.model.EventHandler;

/**
 * Covers: textField, numericField, currencyField, percentField, passwordField, emailField,
 *         phoneNumberField, dateField, timeField, dateTimeField, checkboxField, toggleField,
 *         countrySelector.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedBasicInput(
        String id,
        String type,
        String label,
        String bind,
        JsonNode value,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean enabled,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean readOnly,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean required,
        String placeholder,
        String helperText,
        String tooltip,
        String format,
        String currency,
        EventHandler onChange
) implements EvaluatedComponent {}
