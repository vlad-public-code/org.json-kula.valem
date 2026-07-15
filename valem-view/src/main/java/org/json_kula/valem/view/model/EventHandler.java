package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Defines what happens when a UI event fires.
 * mutations: JSONata expression evaluated against model state; result is a map of {"$.path": value} mutations.
 * navigate: view id to activate after the mutations are applied.
 */
public record EventHandler(
        String mutations,
        String navigate
) {
    @JsonCreator
    public static EventHandler of(
            @JsonProperty("mutations") String mutations,
            @JsonProperty("navigate")  String navigate) {
        return new EventHandler(mutations, navigate);
    }
}
