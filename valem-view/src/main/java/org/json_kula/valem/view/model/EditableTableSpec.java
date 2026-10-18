package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code editableTable} — a grid whose cells hold components, so a value is edited where it is
 * read rather than in a separate form.
 *
 * <p>Columns ({@code gridColumns}) each carry a {@code header} and the components drawn in their
 * cells. {@code rowHeader}, when present, is the leading column of row headers. Rows come from one
 * of two places:
 * <ul>
 *   <li><b>{@code bind}</b> (an array): one row per element. Cell components bind the element's
 *       fields through the array wildcard ({@code $.items[*].price}), and the renderer replaces
 *       {@code [*]} with the row's index — the same scoping as an inline {@code sectionList}.
 *       {@code canAdd} / {@code canRemove} add the list's row controls.</li>
 *   <li><b>{@code gridRows}</b>: a fixed set of rows, each with its own {@code header} and its
 *       {@code cells} keyed by column {@code id}. Cell components bind absolute paths.</li>
 * </ul>
 */
public record EditableTableSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        GridColumnSpec rowHeader,
        List<GridColumnSpec> gridColumns,
        List<GridRowSpec> gridRows,
        JsonNode canAdd,
        JsonNode canRemove,
        String addLabel,
        String removeLabel,
        String emptyText,
        String tooltip
) implements ComponentSpec {
    public EditableTableSpec {
        ComponentSpec.requireIdentity(id, type);
        gridColumns = gridColumns != null ? List.copyOf(gridColumns) : null;
        gridRows = gridRows != null ? List.copyOf(gridRows) : null;
    }
}
