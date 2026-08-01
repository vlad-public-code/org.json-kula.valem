package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One curated guidance topic: a stable {@code id} the model passes to {@code get_domain_guidance}, a
 * one-line {@code description} (the menu the model chooses from), and the vetted {@code instructions}
 * the tool returns for it.
 *
 * <p>Topics are DATA: builtin ones load from a JSON resource and an operator can add or override them
 * via config (see {@link DomainGuidanceCatalog}). Instructions may be given as a single string, or —
 * for readability in JSON — as {@code instructionLines} joined with newlines.
 */
public record DomainGuidanceTopic(String id, String description, String instructions) {

    @JsonCreator
    static DomainGuidanceTopic fromJson(
            @JsonProperty("id") String id,
            @JsonProperty("description") String description,
            @JsonProperty("instructions") String instructions,
            @JsonProperty("instructionLines") List<String> instructionLines) {
        String text = (instructions != null && !instructions.isBlank())
                ? instructions
                : (instructionLines == null ? "" : String.join("\n", instructionLines));
        return new DomainGuidanceTopic(
                id == null ? "" : id.trim(),
                description == null ? "" : description,
                text == null ? "" : text);
    }

    /** True once this topic is usable — a non-blank id and non-blank instructions. */
    boolean isValid() {
        return id != null && !id.isBlank() && instructions != null && !instructions.isBlank();
    }
}
