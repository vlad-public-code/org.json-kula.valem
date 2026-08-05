package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One resolved citation of an evaluated {@code sourceList}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedSourceItem(
        String label,
        String url,
        String date
) {}
