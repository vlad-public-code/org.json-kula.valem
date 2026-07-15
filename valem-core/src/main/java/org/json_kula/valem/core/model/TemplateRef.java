package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A branch's declared parent — {@code template: { ref: "<coordinate>" }} (references design §5.1).
 * Authored input; resolved through the repository chain at create/load time and flattened via
 * {@code SpecEvolution} into a self-contained materialized spec. Pure descriptor — core validates the
 * coordinate's shape only, never resolves it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplateRef(String ref) {
    @JsonCreator
    public static TemplateRef of(@JsonProperty("ref") String ref) {
        return new TemplateRef(ref);
    }
}
