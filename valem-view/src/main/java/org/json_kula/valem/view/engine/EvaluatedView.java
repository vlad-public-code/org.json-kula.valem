package org.json_kula.valem.view.engine;

import java.util.List;

/**
 * A ViewSpec with all dynamic expressions resolved.
 * This is the renderer-agnostic contract served by REST and console endpoints.
 */
public record EvaluatedView(
        String modelId,
        String viewId,
        String title,
        String layout,
        List<EvaluatedComponent> components
) {}
