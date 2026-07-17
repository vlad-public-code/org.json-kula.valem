package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the item-3 split: expression repair fires only at real expression locations, so
 * user-visible non-expression strings (constraint messages, view helper text) survive byte-for-byte.
 */
class ExpressionRepairerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Single-expression transform (bare decoded string in, bare decoded string out) ──────────

    @Test
    void repair_fixes_jsonata_syntax_on_a_bare_expression() {
        assertThat(ExpressionRepairer.repair("counter mod 2 = 0")).isEqualTo("counter % 2 = 0");
        assertThat(ExpressionRepairer.repair("$x == 1")).isEqualTo("$x = 1");
        assertThat(ExpressionRepairer.repair("$toInteger(value)")).isEqualTo("$number(value)");
        assertThat(ExpressionRepairer.repair("not leaseOption")).isEqualTo("$not(leaseOption)");
    }

    @Test
    void repair_converts_lambda_paren_body_to_brace() {
        assertThat(ExpressionRepairer.repair("$map([1..n], function($m) ($m * 2))"))
                .isEqualTo("$map([1..n], function($m) {$m * 2})");
    }

    @Test
    void repair_leaves_a_correct_expression_untouched() {
        String ok = "order.subtotal + order.tax";
        assertThat(ExpressionRepairer.repair(ok)).isEqualTo(ok);
    }

    // ── Tree walk: only expression locations are touched ───────────────────────────────────────

    @Test
    void constraint_expr_is_repaired_but_constraint_message_survives() {
        String json = """
                {"constraints":[
                   {"id":"c1","expr":"status == \\"draft\\"","message":"status == draft is not allowed"}
                ]}""";
        String out = SpecGenerator.fixExpressions(json);
        // The expr gets == -> = ; the human message keeps its literal == byte-for-byte.
        assertThat(out).contains("\"message\":\"status == draft is not allowed\"");
        assertThat(out).contains("status = ");
    }

    @Test
    void derivation_expr_is_repaired_in_a_full_spec() {
        String json = """
                {"id":"m","schema":{},
                 "derivations":[{"path":"$.r","expr":"a mod b"}]}""";
        String out = SpecGenerator.fixExpressions(json);
        assertThat(out).contains("a % b");
    }

    @Test
    void view_helper_text_with_operators_survives_byte_for_byte() {
        // helperText / label(text)-as-static prose is not an expression the walk rewrites; the
        // reactive "visible" expression is.
        String json = """
                {"id":"m","schema":{},
                 "viewDefinition":{"views":[{"id":"v","components":[
                    {"id":"c","type":"numericField","helperText":"enter a != 0 value, x mod y matters",
                     "visible":"count != 0 and count mod 2 = 1"}
                 ]}]}}""";
        String out = SpecGenerator.fixExpressions(json);
        assertThat(out).contains("\"helperText\":\"enter a != 0 value, x mod y matters\"");
        assertThat(out).contains("count % 2 = 1");
    }

    @Test
    void unparseable_response_still_gets_the_raw_pass_rescue() {
        // Broken JSON (missing closing brace) must not be dropped: the raw whole-document passes
        // still run, fixing the mod keyword inside the (un-parseable) text.
        String broken = "{\"derivations\":[{\"expr\":\"a mod b\"}";
        String out = SpecGenerator.fixExpressions(broken);
        assertThat(out).contains("a % b");
    }

    @Test
    void effect_trigger_and_payload_expressions_are_repaired() {
        String json = """
                {"id":"m","schema":{},
                 "effects":[{"id":"e","executor":"caller","trigger":"score == 100",
                             "payload":{"pct":"count == total"}}]}""";
        String out = SpecGenerator.fixExpressions(json);
        assertThat(out).contains("\"trigger\":\"score = 100\"");
        assertThat(out).contains("\"pct\":\"count = total\"");
    }
}
