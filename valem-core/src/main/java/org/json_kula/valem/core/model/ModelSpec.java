package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The declarative model specification produced by an LLM and executed by the Valem runtime.
 * Deserialised from JSON; all collections are immutable after construction.
 */
public record ModelSpec(
        String id,
        String version,
        JsonNode schema,                          // standard JSON Schema (Draft 2020-12) document
        List<DerivationSpec>     derivations,
        List<MetaDerivationSpec> metaDerivations,
        List<ConstraintSpec>     constraints,
        List<TestCase>           tests,
        List<DefaultValueSpec>   defaultValues,    // default rules for newly-created containers (a $ rule seeds at creation)
        Map<String, JsonNode>    constants,        // named immutable values (any JSON type), bound as $const in expressions
        JsonNode                 viewDefinition,   // raw UI view definition; parsed by valem-view
        List<EffectSpec>         effects,          // effect requests emitted by the core, executed by a shell (server/caller)
        TemplateRef              template,         // optional: branch parent (authored); resolved + inlined at create/load
        List<LineageEntry>       lineage           // read-only pinned ancestor chain (materializer-written)
) {
    @JsonCreator
    public static ModelSpec of(
            @JsonProperty(value = "id",      required = true) String id,
            @JsonProperty("version")                           String version,
            @JsonProperty(value = "schema",  required = true) JsonNode schema,
            @JsonProperty("derivations")                       List<DerivationSpec>     derivations,
            @JsonProperty("metaDerivations")                   List<MetaDerivationSpec> metaDerivations,
            @JsonProperty("constraints")                       List<ConstraintSpec>     constraints,
            @JsonProperty("tests")                             List<TestCase>           tests,
            @JsonProperty("defaultValues")                     List<DefaultValueSpec>   defaultValues,
            @JsonProperty("constants")                         Map<String, JsonNode>    constants,
            @JsonProperty("viewDefinition")                    JsonNode                 viewDefinition,
            @JsonProperty("effects")                           List<EffectSpec>         effects,
            @JsonProperty("template")                          TemplateRef              template,
            @JsonProperty("lineage")                           List<LineageEntry>       lineage,
            // Removed: seeding is now a defaultValues rule with path "$". Accepted only to reject a
            // spec that still carries the legacy field, so the failure is explicit (not a silent drop).
            @JsonProperty("initialState")                      Map<String, JsonNode>    initialState,
            // Removed: actions are superseded by effects with executor "caller". Accepted (and rejected
            // when non-empty) so a legacy spec fails loudly with a migration pointer, and an empty
            // "actions": [] left in an old spec/fixture still deserializes.
            @JsonProperty("actions")                           List<JsonNode>           actions
    ) {
        if (initialState != null && !initialState.isEmpty()) {
            throw new IllegalArgumentException(
                    "initialState has been removed; declare a defaultValues rule with path \"$\" "
                    + "whose expr returns the seed object instead.");
        }
        if (actions != null && !actions.isEmpty()) {
            throw new IllegalArgumentException(
                    "actions has been removed; declare an effect with executor \"caller\" "
                    + "(emit + payload) instead.");
        }
        return new ModelSpec(
                id,
                version != null ? version : "1.0.0",
                schema,
                derivations      != null ? List.copyOf(derivations)      : List.of(),
                metaDerivations  != null ? List.copyOf(metaDerivations)  : List.of(),
                constraints      != null ? List.copyOf(constraints)      : List.of(),
                tests            != null ? List.copyOf(tests)            : List.of(),
                defaultValues    != null ? List.copyOf(defaultValues)    : List.of(),
                constants        != null ? Map.copyOf(constants)         : Map.of(),
                viewDefinition,
                effects          != null ? List.copyOf(effects)          : List.of(),
                template,
                lineage          != null ? List.copyOf(lineage)          : List.of()
        );
    }
}

