package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record MenuItemSpec(
        String label,
        String targetView,
        String icon
) {
    @JsonCreator
    public static MenuItemSpec of(
            @JsonProperty("label")      String label,
            @JsonProperty("targetView") String targetView,
            @JsonProperty("icon")       String icon) {
        return new MenuItemSpec(label, targetView, icon);
    }
}
