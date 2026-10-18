package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** An evaluated fixed {@code editableTable} row: its header and per-column cell components. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedGridRow(
        String id,
        String header,
        Map<String, List<EvaluatedComponent>> cells
) {}
