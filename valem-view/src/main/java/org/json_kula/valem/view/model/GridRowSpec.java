package org.json_kula.valem.view.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One fixed row of an {@code editableTable}: its {@code header} (shown in the row-header column)
 * and the components of each cell, keyed by {@link GridColumnSpec#id()}. A column with no entry
 * here falls back to that column's own {@code components}.
 */
public record GridRowSpec(
        String id,
        String header,
        Map<String, List<ComponentSpec>> cells
) {
    public GridRowSpec {
        if (cells != null) {
            Map<String, List<ComponentSpec>> copy = new LinkedHashMap<>();
            cells.forEach((k, v) -> copy.put(k, v != null ? List.copyOf(v) : List.of()));
            cells = java.util.Collections.unmodifiableMap(copy);
        }
    }
}
