package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Top-level view definition embedded in ModelSpec.viewDefinition.
 * Describes the available views and which one to show by default.
 */
public record ViewDefinition(
        String renderer,
        List<ViewSpec> views,
        String defaultView
) {
    @JsonCreator
    public static ViewDefinition of(
            @JsonProperty("renderer")    String renderer,
            @JsonProperty("views")       List<ViewSpec> views,
            @JsonProperty("defaultView") String defaultView
    ) {
        return new ViewDefinition(
                renderer != null ? renderer : "builtin",
                views    != null ? List.copyOf(views) : List.of(),
                defaultView
        );
    }
}
