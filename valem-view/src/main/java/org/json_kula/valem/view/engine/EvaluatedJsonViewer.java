package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Covers: jsonViewer. {@code value} is the bound subtree of the merged document — derived values
 * already spliced in — so the panel shows the document the engine actually evaluated against.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedJsonViewer(
        String id,
        String type,
        String label,
        String bind,
        JsonNode value,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean collapsed,
        Integer maxDepth,
        String tooltip
) implements EvaluatedComponent {}
