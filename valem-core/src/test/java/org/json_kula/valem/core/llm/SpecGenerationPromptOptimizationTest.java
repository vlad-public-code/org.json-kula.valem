package org.json_kula.valem.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the LLM-token-consumption optimizations of {@link SpecGenerationPrompt}:
 * <ol>
 *   <li>decorative box-drawing frames removed from the always-sent system context;</li>
 *   <li>a second cache tier ({@code sessionContext}) that carries the session-stable current-spec
 *       context on the evolution path, byte-identical between the evolution and evolution-repair
 *       prompts so a provider can cache it across the whole retry loop;</li>
 *   <li>structural scaffolding trimmed (the response schema enforces shape);</li>
 *   <li>duplicated JSONata rules stated once;</li>
 *   <li>the truncated-response repair prompt kept short;</li>
 *   <li>tool-usage guidance compressed but intact.</li>
 * </ol>
 */
class SpecGenerationPromptOptimizationTest {

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // ── Item 1: no decorative box-drawing frames ────────────────────────────────

    @Test
    void system_context_and_view_catalog_have_no_box_drawing_frames() {
        for (String s : List.of(SpecGenerationPrompt.SYSTEM_CONTEXT,
                                 SpecGenerationPrompt.SYSTEM_CONTEXT_VIEW)) {
            assertThat(s)
                    .doesNotContain("╔").doesNotContain("╗").doesNotContain("╚")
                    .doesNotContain("╝").doesNotContain("║").doesNotContain("═");
        }
    }

    // ── Item 4: JSONata rules stated once, semantics preserved ──────────────────

    @Test
    void critical_jsonata_rules_are_stated_exactly_once() {
        String sys = SpecGenerationPrompt.SYSTEM_CONTEXT;
        assertThat(count(sys, "lambda body MUST use")).isEqualTo(1);
        assertThat(count(sys, "multi-statement sequences REQUIRE outer parentheses")).isEqualTo(1);
        // the semantics behind those rules are still present
        assertThat(sys).contains("Expected LBRACE").contains("Syntax error at ;");
    }

    // ── Item 3 + 6: scaffolding trimmed but semantics + tool guidance intact ────

    @Test
    void schema_shape_is_delegated_to_structured_output_but_semantics_stay() {
        String sys = SpecGenerationPrompt.SYSTEM_CONTEXT;
        assertThat(sys).contains("structured output");           // item 6 note
        assertThat(sys).contains("web_fetch").contains("eval_jsonata");
        // effects semantics survive the trim
        assertThat(sys).contains("caller").contains("timer").contains("SSRF-guarded");
        // self-test rules (relied on elsewhere) survive
        assertThat(sys).contains("expect ONLY scalar").contains("NEVER assert an").contains("$now()");
    }

    // ── Item 5: truncated-repair prompt is short and still constrains ───────────

    @Test
    void truncated_repair_prompt_is_short_and_still_constrains_size() {
        var parts = SpecGenerationPrompt.repairPromptTruncatedParts("m", "a mortgage calculator", false);
        assertThat(parts.hasSessionContext()).isFalse();
        assertThat(parts.user())
                .contains("MUCH SHORTER")
                .contains("a mortgage calculator")
                .contains("derivations");
        // materially compact — the whole size budget now fits well under this bound
        assertThat(parts.user().length()).isLessThan(600);
    }

    // ── Item 2: three-tier PromptParts ──────────────────────────────────────────

    @Test
    void two_arg_prompt_parts_keeps_an_empty_session_context() {
        var p = new SpecGenerationPrompt.PromptParts("SYS", "USER");
        assertThat(p.sessionContext()).isEmpty();
        assertThat(p.hasSessionContext()).isFalse();
        assertThat(p.concatenated()).isEqualTo("SYS\n\nUSER");
    }

    @Test
    void three_arg_prompt_parts_concatenates_all_tiers_in_order() {
        var p = new SpecGenerationPrompt.PromptParts("SYS", "CTX", "USER");
        assertThat(p.hasSessionContext()).isTrue();
        assertThat(p.concatenated()).isEqualTo("SYS\n\nCTX\n\nUSER");
    }

    @Test
    void initial_and_repair_prompts_have_no_session_context() {
        assertThat(SpecGenerationPrompt.initialPromptParts("m", "a counter", false).hasSessionContext())
                .isFalse();
        assertThat(SpecGenerationPrompt.repairPromptParts("m", "{}", List.of(), false).hasSessionContext())
                .isFalse();
    }

    @Test
    void evolution_hoists_current_spec_into_session_context_out_of_the_volatile_user_turn() {
        var parts = SpecGenerationPrompt.evolutionPromptParts(
                "m", "{\"id\":\"m\",\"marker\":42}", "add a total", false, List.of("$.total"));

        assertThat(parts.hasSessionContext()).isTrue();
        // the (large, stable) current spec + derived-paths live in the cached session tier
        assertThat(parts.sessionContext())
                .contains("{\"id\":\"m\",\"marker\":42}")
                .contains("already DERIVED")
                .contains("$.total");
        // the volatile user turn no longer re-carries the spec JSON on every retry
        assertThat(parts.user()).doesNotContain("marker").contains("add a total");
        // concatenation still reproduces the complete prompt
        assertThat(parts.concatenated())
                .contains("marker").contains("add a total").contains("already DERIVED");
    }

    @Test
    void evolution_and_evolution_repair_share_a_byte_identical_session_context() {
        String spec = "{\"id\":\"m\",\"schema\":{},\"big\":\"payload-that-should-be-cached-once\"}";
        List<String> derived = List.of("$.total", "$.tax");

        var gen = SpecGenerationPrompt.evolutionPromptParts("m", spec, "add x", false, derived);
        var repair = SpecGenerationPrompt.evolutionRepairPromptParts(
                "m", spec, "add x", "{\"upsertDerivations\":[]}", "RULE-NAMED FEEDBACK", false, derived);

        // Identical → the provider caches system+sessionContext once and re-reads it across the
        // whole evolve→repair→repair loop instead of re-billing the spec each attempt.
        assertThat(repair.sessionContext()).isEqualTo(gen.sessionContext());
        // and the repair's volatile tier carries only the per-attempt bits
        assertThat(repair.user()).contains("RULE-NAMED FEEDBACK").contains("upsertDerivations");
        assertThat(repair.user()).doesNotContain("payload-that-should-be-cached-once");
    }
}
