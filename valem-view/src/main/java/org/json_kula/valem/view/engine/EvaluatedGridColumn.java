package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** An evaluated {@code editableTable} column (or row-header column). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedGridColumn(
        String id,
        String header,
        String width,
        String align,
        List<EvaluatedComponent> components
) {}
