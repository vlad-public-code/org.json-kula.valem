package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.json_kula.valem.view.model.EventHandler;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedSectionList(
        String id,
        String type,
        String label,
        String bind,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        List<EvaluatedComponent> components,
        String layout,
        Integer columns,
        boolean canAdd,
        boolean canRemove,
        String addLabel,
        String removeLabel,
        EventHandler onChange
) implements EvaluatedComponent {}
