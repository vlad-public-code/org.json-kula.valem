package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Covers {@code group}, {@code fieldSet}, {@code card}, {@code toolbar}, {@code buttonGroup},
 * {@code tabs}, {@code tabItem}, {@code accordion}, {@code collapsible} and {@code sectionItem} —
 * containers of sub-components. {@code bind} is set only by {@code sectionItem}, which edits one
 * array element.
 *
 * <p>{@code label} is carried because for a {@code card}, a {@code tabItem} and a
 * {@code collapsible} it is the visible heading — the tab's title, the panel's summary row — not
 * decoration a renderer may drop. It is populated for every container type; a plain {@code group}
 * simply has none to show.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedContainer(
        String id,
        String type,
        String label,
        String bind,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String layout,
        Integer columns,
        List<EvaluatedComponent> components,
        String legend,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean collapsed
) implements EvaluatedComponent {}
