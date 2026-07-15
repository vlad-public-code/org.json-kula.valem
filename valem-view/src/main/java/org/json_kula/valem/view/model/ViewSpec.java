package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ViewSpec(
        String id,
        String label,
        String layout,
        Integer columns,
        List<ComponentSpec> components,
        EventHandler onOpen,
        EventHandler onClose
) {
    @JsonCreator
    public static ViewSpec of(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty("label")       String label,
            @JsonProperty("layout")      String layout,
            @JsonProperty("columns")     Integer columns,
            @JsonProperty("components")  List<ComponentSpec> components,
            @JsonProperty("onOpen")      EventHandler onOpen,
            @JsonProperty("onClose")     EventHandler onClose
    ) {
        return new ViewSpec(
                id,
                label,
                layout != null ? layout : "vertical",
                columns,
                components != null ? List.copyOf(components) : List.of(),
                onOpen,
                onClose
        );
    }
}
