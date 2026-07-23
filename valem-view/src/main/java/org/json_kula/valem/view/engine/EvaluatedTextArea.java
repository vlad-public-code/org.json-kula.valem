package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.view.model.EventHandler;

/** Covers: textAreaField, richTextField. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedTextArea(
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
        Integer rows,
        String toolbar,
        EventHandler onChange
) implements EvaluatedComponent {}
