package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.view.model.EventHandler;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedFileUpload(
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
        String accept,
        Boolean multiple,
        Integer minFiles,
        Integer maxFiles,
        Long minSize,
        Long maxSize,
        String allowedMediaTypes,
        String helperText,
        String tooltip,
        EventHandler onChange
) implements EvaluatedComponent {}
