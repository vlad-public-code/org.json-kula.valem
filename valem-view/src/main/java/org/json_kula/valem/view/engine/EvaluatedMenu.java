package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.json_kula.valem.view.model.MenuItemSpec;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedMenu(
        String id,
        String type,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String orientation,
        List<MenuItemSpec> menuItems
) implements EvaluatedComponent {}
