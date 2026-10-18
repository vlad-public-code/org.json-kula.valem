package org.json_kula.valem.view.model;

import java.util.List;

/**
 * One column of an {@code editableTable}, or its {@code rowHeader}. {@code components} are what
 * every cell of the column shows; a fixed {@code gridRows} row may override them per cell.
 * {@code id} keys the column in {@link GridRowSpec#cells()}.
 */
public record GridColumnSpec(
        String id,
        String header,
        String width,
        String align,
        List<ComponentSpec> components
) {
    public GridColumnSpec {
        components = components != null ? List.copyOf(components) : null;
    }
}
