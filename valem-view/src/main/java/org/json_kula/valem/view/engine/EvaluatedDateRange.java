package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.view.model.EventHandler;

/**
 * Covers: dateRangeField.
 *
 * <p>Carries both ends resolved — {@code valueFrom} / {@code valueTo} — rather than a single
 * {@code value}, because the two live at separate paths in the document.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedDateRange(
        String id,
        String type,
        String label,
        String bind,
        String bindFrom,
        String bindTo,
        JsonNode valueFrom,
        JsonNode valueTo,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean enabled,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean readOnly,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean required,
        String fromLabel,
        String toLabel,
        String helperText,
        String tooltip,
        String minDate,
        String maxDate,
        EventHandler onChange
) implements EvaluatedComponent {}
