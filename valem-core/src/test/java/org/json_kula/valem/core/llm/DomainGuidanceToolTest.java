package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.LlmClient.ToolCall;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the tool-based, language-agnostic, config-extensible domain-guidance mechanism:
 * {@link DomainGuidanceCatalog} (builtin JSON resource + operator overrides) and
 * {@link DomainGuidanceTool}.
 */
class DomainGuidanceToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DomainGuidanceCatalog builtin = DomainGuidanceCatalog.builtin(MAPPER);
    private final DomainGuidanceTool tool = new DomainGuidanceTool(builtin);

    // ── Builtin catalog (loaded from the JSON resource) ──────────────────────────

    @Test
    void builtin_catalog_loads_topics_from_the_json_resource() {
        assertThat(builtin.size()).isGreaterThanOrEqualTo(14);
        // well-known original + newer common-pattern ids all resolve, with non-blank instructions
        for (String id : java.util.List.of("regulated_charge", "amortization_schedule",
                "percentage", "unit_conversion", "compound_growth", "weighted_average",
                "eligibility", "proration")) {
            assertThat(builtin.byId(id)).as("topic '%s'", id).isPresent();
            assertThat(builtin.instructionsFor(java.util.List.of(id))).as("instructions for '%s'", id)
                    .isNotBlank();
        }
        assertThat(builtin.instructionsFor(java.util.List.of("regulated_charge")))
                .contains("OFFICIAL, PUBLISHED CHARGE");
        assertThat(builtin.instructionsFor(java.util.List.of("compound_growth")))
                .contains("COMPOUND GROWTH");
    }

    @Test
    void instructions_for_resolves_dedupes_and_ignores_unknown() {
        assertThat(builtin.instructionsFor(java.util.List.of(
                "regulated_charge", "regulated_charge", "not_a_topic")))
                .contains("OFFICIAL, PUBLISHED CHARGE")
                .containsOnlyOnce("OFFICIAL, PUBLISHED CHARGE");
        assertThat(builtin.instructionsFor(java.util.List.of("not_a_topic"))).isEmpty();
        assertThat(builtin.instructionsFor(java.util.List.of())).isEmpty();
    }

    @Test
    void byId_is_case_insensitive() {
        assertThat(builtin.byId("REGULATED_CHARGE")).isPresent();
        assertThat(builtin.byId(" amortization_schedule ")).isPresent();
        assertThat(builtin.byId("nope")).isEmpty();
    }

    // ── Operator overrides / extension (config-supplied JSON) ────────────────────

    @Test
    void overrides_add_new_topics_and_replace_builtin_ones_by_id() {
        String overrides = """
            [
              { "id": "widget_levy", "description": "A made-up levy", "instructions": "WIDGET LEVY RULES" },
              { "id": "regulated_charge", "description": "override", "instructions": "REPLACED CHARGE TEXT" }
            ]
            """;
        DomainGuidanceCatalog merged = builtin.withOverridesJson(overrides, MAPPER);

        // new topic added
        assertThat(merged.byId("widget_levy")).isPresent();
        assertThat(merged.instructionsFor(java.util.List.of("widget_levy"))).contains("WIDGET LEVY RULES");
        // builtin replaced in place (id kept its position, content overridden)
        assertThat(merged.instructionsFor(java.util.List.of("regulated_charge")))
                .isEqualTo("REPLACED CHARGE TEXT");
        assertThat(merged.size()).isEqualTo(builtin.size() + 1);
        // the original builtin catalog is unchanged (withOverridesJson returns a copy)
        assertThat(builtin.instructionsFor(java.util.List.of("regulated_charge")))
                .contains("OFFICIAL, PUBLISHED CHARGE");
    }

    @Test
    void mixed_case_topic_ids_resolve_consistently_via_id_menu_and_instructions() {
        // An operator supplies a MixedCase id: it must be canonicalised so byId(), ids() (the tool enum)
        // and instructionsFor() all agree — a lower-cased request must still return its instructions.
        DomainGuidanceCatalog merged = builtin.withOverridesJson("""
            [ { "id": "Widget_Levy", "description": "d", "instructions": "WIDGET LEVY RULES" } ]
            """, MAPPER);
        assertThat(merged.ids()).contains("widget_levy");
        assertThat(merged.byId("Widget_Levy")).isPresent();
        assertThat(merged.instructionsFor(java.util.List.of("widget_levy"))).contains("WIDGET LEVY RULES");
        assertThat(merged.instructionsFor(java.util.List.of("WIDGET_LEVY"))).contains("WIDGET LEVY RULES");
    }

    @Test
    void malformed_or_blank_overrides_are_ignored() {
        assertThat(builtin.withOverridesJson("", MAPPER).size()).isEqualTo(builtin.size());
        assertThat(builtin.withOverridesJson("not json", MAPPER).size()).isEqualTo(builtin.size());
    }

    // ── Tool definition ──────────────────────────────────────────────────────────

    @Test
    void definition_enumerates_the_catalog_ids_and_lists_the_menu() {
        var def = tool.definition();
        assertThat(def.name()).isEqualTo(DomainGuidanceTool.TOOL_NAME);
        assertThat(def.description()).contains("regulated_charge").contains("amortization_schedule");
        var en = def.inputSchema().path("properties").path("topics").path("items").path("enum");
        assertThat(en.isArray()).isTrue();
        assertThat(en).hasSize(builtin.size());
    }

    // ── Executor ─────────────────────────────────────────────────────────────────

    @Test
    void executor_returns_instructions_and_records_them_for_reinjection() {
        DomainGuidanceTool.GuidanceExecutor ex = tool.newExecutor();
        String out = ex.execute(callTopics("regulated_charge", "classification"));
        assertThat(out).contains("OFFICIAL, PUBLISHED CHARGE").contains("DERIVES a label");
        assertThat(ex.resolvedGuidance())
                .contains("OFFICIAL, PUBLISHED CHARGE").contains("DERIVES a label");
    }

    @Test
    void executor_accumulates_across_calls_and_handles_bad_input() {
        DomainGuidanceTool.GuidanceExecutor ex = tool.newExecutor();
        ex.execute(callTopics("regulated_charge"));
        ex.execute(callTopics("date_math"));
        assertThat(ex.resolvedGuidance())
                .contains("OFFICIAL, PUBLISHED CHARGE").contains("DATE ARITHMETIC");

        assertThat(tool.newExecutor().execute(callTopics())).contains("non-empty");
        DomainGuidanceTool.GuidanceExecutor ex2 = tool.newExecutor();
        assertThat(ex2.execute(callTopics("bogus"))).contains("known topic");
        assertThat(ex2.resolvedGuidance()).isEmpty();
    }

    @Test
    void guidance_tool_is_not_offered_on_repairs() {
        // Guidance persists across repairs via SpecGenerator re-injection, not by re-calling the tool.
        assertThat(tool.repairDefinitions()).isEmpty();
    }

    private static ToolCall callTopics(String... topics) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = args.putArray("topics");
        for (String t : topics) arr.add(t);
        return new ToolCall("id1", DomainGuidanceTool.TOOL_NAME, args);
    }
}
