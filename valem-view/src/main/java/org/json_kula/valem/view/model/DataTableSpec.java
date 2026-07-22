package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** {@code dataTable} — a tabular view over a bound array. */
public record DataTableSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        List<ColumnSpec> tableColumns,
        Integer pageSize,
        String tooltip
) implements ComponentSpec {
    public DataTableSpec {
        ComponentSpec.requireIdentity(id, type);
        tableColumns = tableColumns != null ? List.copyOf(tableColumns) : null;
    }
}
