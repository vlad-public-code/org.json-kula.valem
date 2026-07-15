package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.llm.SpecGenerator.GenerationResult;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── JSON repair ───────────────────────────────────────────────────────────

    @Test
    void repair_fixes_missing_close_quote_before_brace() {
        // Simulates "type":"boolean} — missing close-quote is the #1 LLM JSON bug
        assertThat(SpecGenerator.repairJson("{\"type\":\"boolean}"))
                .isEqualTo("{\"type\":\"boolean\"}");
    }

    @Test
    void repair_fixes_missing_close_quote_before_comma() {
        assertThat(SpecGenerator.repairJson("{\"a\":\"string,\"b\":1}"))
                .isEqualTo("{\"a\":\"string\",\"b\":1}");
    }

    @Test
    void repair_leaves_valid_json_unchanged() {
        String valid = "{\"type\":\"boolean\",\"default\":true}";
        assertThat(SpecGenerator.repairJson(valid)).isEqualTo(valid);
    }

    @Test
    void repair_does_not_alter_numeric_or_path_values() {
        // Numbers and $-paths must not be touched
        String json = "{\"year\":2024,\"path\":\"$.vehicle.year\"}";
        assertThat(SpecGenerator.repairJson(json)).isEqualTo(json);
    }

    @Test
    void repair_quotes_unquoted_expression_values_in_action_payload() {
        // LLM omits quotes on JSONata expressions used as payload values
        String json = "{\"payload\":{\"loanAmount\": loanAmount - downPayment,\"totalInterest\": totalInterest}}";
        assertThat(SpecGenerator.repairJson(json))
                .isEqualTo("{\"payload\":{\"loanAmount\": \"loanAmount - downPayment\",\"totalInterest\": \"totalInterest\"}}");
    }

    @Test
    void repair_does_not_quote_json_booleans_or_nulls() {
        String json = "{\"taxExempt\": true,\"value\": null,\"count\": 42}";
        assertThat(SpecGenerator.repairJson(json)).isEqualTo(json);
    }

    @Test
    void generate_succeeds_when_llm_produces_missing_close_quote() {
        // LLM response with "type":"boolean} — repair should allow parse to succeed
        LlmClient stub = prompt -> "{\"id\":\"m\",\"schema\":{\"type\":\"object\",\"properties\":{\"active\":{\"type\":\"boolean}}}}}";
        var result = new SpecGenerator(stub, MAPPER, 1).generate("m", "desc");
        assertThat(result).isInstanceOf(GenerationResult.Success.class);
    }

    // ── Lambda body fix ───────────────────────────────────────────────────────

    @Test
    void fix_lambda_body_converts_paren_to_brace() {
        // LLM writes function($m) (...) but our runtime requires function($m) {...}
        String input = "\"$map([1..n], function($m) ($m * 2))\"";
        assertThat(SpecGenerator.fixLambdaBodies(input))
                .isEqualTo("\"$map([1..n], function($m) {$m * 2})\"");
    }

    @Test
    void fix_lambda_body_handles_nested_parens_in_body() {
        // Inner ($m - 1) must NOT confuse the bracket counter
        String input = "\"function($m) ($i := $m * 2; $i + ($m - 1))\"";
        assertThat(SpecGenerator.fixLambdaBodies(input))
                .isEqualTo("\"function($m) {$i := $m * 2; $i + ($m - 1)}\"");
    }

    @Test
    void fix_lambda_body_handles_object_literal_result_inside_paren_body() {
        // Simulates the car-loan schedule pattern the LLM generates
        String input = "\"function($m) ($i := rate; {\\\"interest\\\": $i, \\\"month\\\": $m})\"";
        String fixed = SpecGenerator.fixLambdaBodies(input);
        assertThat(fixed).contains("function($m) {");
        assertThat(fixed).doesNotContain("function($m) (");
    }

    @Test
    void fix_lambda_body_does_not_change_already_valid_brace_body() {
        // function($m) {body} is already correct — must not be double-wrapped
        String input = "\"$map([1..n], function($m) {$m * 2})\"";
        assertThat(SpecGenerator.fixLambdaBodies(input)).isEqualTo(input);
    }

    @Test
    void fix_expressions_calls_fix_lambda_bodies() {
        // Integration: fixExpressions() must invoke fixLambdaBodies()
        String input = "\"$map([1..n], function($m) ($m * 2))\"";
        assertThat(SpecGenerator.fixExpressions(input)).contains("function($m) {$m * 2}");
    }

    @Test
    void fix_expressions_converts_mod_keyword_to_percent_operator() {
        // JSONata modulo is %, not the SQL/BASIC "mod" keyword (a common LLM mistake).
        assertThat(SpecGenerator.fixExpressions("\"counter mod 2 = 0\""))
                .contains("counter % 2");
        assertThat(SpecGenerator.fixExpressions("\"(a.b) mod count\""))
                .contains("(a.b) % count");
    }

    // ── balanceExpressionParens ────────────────────────────────────────────────

    @Test
    void balance_appends_missing_trailing_paren_for_dollar_sequence_expr() {
        // "($a := 1; $b - (c + d)"  is missing its final ) → balancer appends it.
        assertThat(SpecGenerator.balanceExpressionParens("\"($a := 1; $b - (c + d)\""))
                .isEqualTo("\"($a := 1; $b - (c + d))\"");
        // Two missing closers.
        assertThat(SpecGenerator.balanceExpressionParens("\"($reduce([1..$n], function($a,$m){$a}, 0\""))
                .isEqualTo("\"($reduce([1..$n], function($a,$m){$a}, 0))\"");
    }

    @Test
    void balance_leaves_balanced_expr_and_non_paren_or_dollar_strings_untouched() {
        assertThat(SpecGenerator.balanceExpressionParens("\"($a := 1; $a + 2)\""))
                .isEqualTo("\"($a := 1; $a + 2)\"");          // already balanced
        assertThat(SpecGenerator.balanceExpressionParens("\"order.total + (tax\""))
                .isEqualTo("\"order.total + (tax\"");          // does not start with (
        assertThat(SpecGenerator.balanceExpressionParens("\"(annual rate note\""))
                .isEqualTo("\"(annual rate note\"");           // starts with ( but no $ → prose, untouched
    }

    @Test
    void balance_ignores_parens_inside_jsonata_string_literals() {
        // The ( inside the single-quoted regex must not count, so this expr is already balanced.
        assertThat(SpecGenerator.balanceExpressionParens("\"($match($, '^[(]') and $x = 1)\""))
                .isEqualTo("\"($match($, '^[(]') and $x = 1)\"");
        // Object-literal keys are JSON-escaped \"…\" JSONata strings; braces must not be miscounted.
        // Here $append(...) is closed; only the outer sequence ( is dropped → exactly one ) appended.
        assertThat(SpecGenerator.balanceExpressionParens("\"($append($a, {\\\"k\\\": $v})\""))
                .isEqualTo("\"($append($a, {\\\"k\\\": $v}))\"");
    }

    @Test
    void hint_for_known_errors_is_appended_to_repair_errors() {
        var rparen = new org.json_kula.valem.core.graph.ModelSpecValidator.ValidationError(
                "derivations[0].expr", "Expected RPAREN but reached end of expression",
                org.json_kula.valem.core.graph.ModelSpecValidator.Severity.ERROR);
        var between = new org.json_kula.valem.core.graph.ModelSpecValidator.ValidationError(
                "constraints[0].expr", "Unexpected token 'between' (position 10)",
                org.json_kula.valem.core.graph.ModelSpecValidator.Severity.ERROR);
        var out = SpecGenerator.annotateErrors(java.util.List.of(rparen, between));
        assertThat(out.get(0).message()).contains("unbalanced brackets").contains("$reduce");
        assertThat(out.get(1).message()).contains("no 'between' operator").contains("x >= lo and x <= hi");
    }

    @Test
    void fix_expressions_does_not_touch_mod_inside_words_or_prose() {
        // "model"/"module" and a description's prose "mod" must be left alone (no operand context).
        assertThat(SpecGenerator.fixExpressions("\"model.modCount\"")).contains("model.modCount");
        assertThat(SpecGenerator.fixExpressions("\"the modulus operation\"")).contains("modulus");
    }

    // ── JSON fence stripping ───────────────────────────────────────────────────

    @Test
    void extracts_json_from_markdown_fence() {
        String raw = """
                ```json
                {"id":"m","schema":{}}
                ```
                """;
        assertThat(SpecGenerator.extractJson(raw)).isEqualTo("{\"id\":\"m\",\"schema\":{}}");
    }

    @Test
    void extracts_json_when_fence_is_preceded_by_preamble_text() {
        // Repair retries from models like Mistral add an explanation before the code block
        String raw = """
                Here is the corrected JSON:
                ```json
                {"id":"m","schema":{}}
                ```
                """;
        assertThat(SpecGenerator.extractJson(raw)).isEqualTo("{\"id\":\"m\",\"schema\":{}}");
    }

    @Test
    void extracts_json_by_brace_matching_when_no_fence() {
        String raw = "Some text before { \"id\": \"m\", \"schema\": {} } some text after";
        assertThat(SpecGenerator.extractJson(raw)).isEqualTo("{ \"id\": \"m\", \"schema\": {} }");
    }

    @Test
    void returns_plain_json_unchanged() {
        String raw = "{\"id\":\"m\",\"schema\":{}}";
        assertThat(SpecGenerator.extractJson(raw)).isEqualTo(raw);
    }

    // ── fixNotKeyword ─────────────────────────────────────────────────────────

    @Test
    void fix_not_keyword_replaces_bare_not_with_dollar_not() {
        assertThat(SpecGenerator.fixNotKeyword("\"not leaseOption\""))
                .isEqualTo("\"$not(leaseOption)\"");
    }

    @Test
    void fix_not_keyword_replaces_not_paren_expression() {
        assertThat(SpecGenerator.fixNotKeyword("\"not (isLease and x > 0)\""))
                .isEqualTo("\"$not(isLease and x > 0)\"");
    }

    @Test
    void fix_not_keyword_replaces_not_in_compound_expression() {
        assertThat(SpecGenerator.fixNotKeyword("\"(not leaseOption) or x > 0\""))
                .isEqualTo("\"($not(leaseOption)) or x > 0\"");
    }

    @Test
    void fix_not_keyword_does_not_alter_dollar_not() {
        String s = "\"$not(leaseOption)\"";
        assertThat(SpecGenerator.fixNotKeyword(s)).isEqualTo(s);
    }

    @Test
    void fix_not_keyword_does_not_alter_word_containing_not() {
        String s = "\"notional > 0\"";
        assertThat(SpecGenerator.fixNotKeyword(s)).isEqualTo(s);
    }

    // ── fixFunctionSequenceBodies ─────────────────────────────────────────────

    @Test
    void fix_function_sequence_bodies_wraps_multi_statement_body() {
        String json = "{\"expr\":\"$reduce(a, function($acc, $m) {$x := 1; $x + $m})\"}";
        assertThat(SpecGenerator.fixFunctionSequenceBodies(json))
                .isEqualTo("{\"expr\":\"$reduce(a, function($acc, $m) {($x := 1; $x + $m)})\"}");
    }

    @Test
    void fix_function_sequence_bodies_leaves_single_expr_body_unchanged() {
        String json = "{\"expr\":\"$map(a, function($m) {$m * 2})\"}";
        assertThat(SpecGenerator.fixFunctionSequenceBodies(json)).isEqualTo(json);
    }

    @Test
    void fix_function_sequence_bodies_leaves_semicolons_in_nested_parens_unchanged() {
        // Semicolon inside inner () must not trigger wrapping of the outer {}
        String json = "{\"expr\":\"$map(a, function($m) {($a := 1; $a + $m)})\"}";
        assertThat(SpecGenerator.fixFunctionSequenceBodies(json)).isEqualTo(json);
    }

    @Test
    void fix_expressions_fixes_negative_exponent() {
        // ** -n on a paren base: wraps AND converts to $reduce with negative (1/reduce) form
        assertThat(SpecGenerator.fixExpressions("\"(1 + $r) ** -$n\""))
                .isEqualTo("\"(1 / $reduce([1..$n], function($acc, $m) {$acc * (1 + $r)}, 1))\"");
    }

    @Test
    void fix_expressions_fixes_negative_numeric_exponent() {
        // base without parens — wrapPower doesn't fire; only the regex runs
        assertThat(SpecGenerator.fixExpressions("\"base ** -2\""))
                .isEqualTo("\"base ** (0 - 2)\"");
    }

    @Test
    void fix_expressions_fixes_negative_exponent_in_parens() {
        // ** (-n) — wraps AND converts to $reduce with negative (1/reduce) form
        assertThat(SpecGenerator.fixExpressions("\"(1 + r) ** (-loanTermMonths)\""))
                .isEqualTo("\"(1 / $reduce([1..loanTermMonths], function($acc, $m) {$acc * (1 + r)}, 1))\"");
    }

    // ── wrapPowerExpressions ──────────────────────────────────────────────────

    @Test
    void wrap_power_expressions_wraps_paren_base_and_paren_exp() {
        String json = "{\"expr\":\"a / (1 - (1 + r) ** (0 - n))\"}";
        assertThat(SpecGenerator.wrapPowerExpressions(json))
                .isEqualTo("{\"expr\":\"a / (1 - ((1 + r) ** (0 - n)))\"}");
    }

    @Test
    void wrap_power_expressions_wraps_paren_base_and_identifier_exp() {
        // (base) ** identifier should also be wrapped
        String json = "{\"expr\":\"a * (1 + r) ** termMonths\"}";
        assertThat(SpecGenerator.wrapPowerExpressions(json))
                .isEqualTo("{\"expr\":\"a * ((1 + r) ** termMonths)\"}");
    }

    @Test
    void wrap_power_expressions_is_idempotent() {
        String json = "{\"expr\":\"((1 + r) ** (0 - n))\"}";
        String once  = SpecGenerator.wrapPowerExpressions(json);
        assertThat(SpecGenerator.wrapPowerExpressions(once)).isEqualTo(once);
    }

    @Test
    void wrap_power_expressions_is_idempotent_for_identifier_exp() {
        String json = "{\"expr\":\"((1 + r) ** termMonths)\"}";
        String once  = SpecGenerator.wrapPowerExpressions(json);
        assertThat(SpecGenerator.wrapPowerExpressions(once)).isEqualTo(once);
    }

    @Test
    void wrap_power_expressions_does_not_affect_outside_json_strings() {
        // ** outside a JSON string value should not be touched
        String json = "{\"description\":\"rate ** 2\",\"expr\":\"(1 + r) ** (n)\"}";
        String fixed = SpecGenerator.wrapPowerExpressions(json);
        assertThat(fixed).contains("\"description\":\"rate ** 2\""); // untouched
        assertThat(fixed).contains("((1 + r) ** (n))");             // wrapped in string value
    }

    // ── convertPowerToReduce ──────────────────────────────────────────────────

    @Test
    void convert_power_to_reduce_handles_identifier_exponent() {
        // ((base) ** n) → $reduce([1..n], fn, 1)
        String json = "{\"expr\":\"p * ((1 + r) ** n)\"}";
        assertThat(SpecGenerator.convertPowerToReduce(json))
                .isEqualTo("{\"expr\":\"p * $reduce([1..n], function($acc, $m) {$acc * (1 + r)}, 1)\"}");
    }

    @Test
    void convert_power_to_reduce_handles_negative_exponent() {
        // ((base) ** (0 - n)) → (1 / $reduce([1..n], fn, 1))
        String json = "{\"expr\":\"1 - ((1 + r) ** (0 - n))\"}";
        assertThat(SpecGenerator.convertPowerToReduce(json))
                .isEqualTo("{\"expr\":\"1 - (1 / $reduce([1..n], function($acc, $m) {$acc * (1 + r)}, 1))\"}");
    }

    @Test
    void convert_power_to_reduce_handles_nested_context() {
        // (((base) ** n) - 1) — the ((base)**n) is inside an outer paren
        String json = "{\"expr\":\"(((1 + r) ** n) - 1)\"}";
        assertThat(SpecGenerator.convertPowerToReduce(json))
                .isEqualTo("{\"expr\":\"($reduce([1..n], function($acc, $m) {$acc * (1 + r)}, 1) - 1)\"}");
    }

    @Test
    void fix_expressions_converts_power_to_reduce_end_to_end() {
        // Full pipeline: (base)**n wrapped then converted — no ** in output
        String json = "{\"expr\":\"P * r * (1 + r) ** n / ((1 + r) ** n - 1)\"}";
        String result = SpecGenerator.fixExpressions(json);
        assertThat(result).doesNotContain("**");
        assertThat(result).contains("$reduce");
    }

    // ── fixBindingCommas ──────────────────────────────────────────────────────

    @Test
    void fix_binding_commas_converts_comma_to_semicolon_in_block() {
        String json = "{\"expr\":\"($r := 0.05, $n := 12, $r * $n)\"}";
        assertThat(SpecGenerator.fixBindingCommas(json))
                .isEqualTo("{\"expr\":\"($r := 0.05; $n := 12; $r * $n)\"}");
    }

    @Test
    void fix_binding_commas_leaves_function_call_args_unchanged() {
        // $substring(str, 0, 4) must keep its commas
        String json = "{\"expr\":\"($r := $substring(str, 0, 4), $n := 12, $r)\"}";
        String result = SpecGenerator.fixBindingCommas(json);
        assertThat(result).contains("$substring(str, 0, 4)");
        assertThat(result).contains("($r := $substring(str, 0, 4); $n := 12; $r)");
    }

    @Test
    void fix_binding_commas_leaves_array_commas_unchanged() {
        // [1, 2, 3] inside a binding block must keep its commas
        String json = "{\"expr\":\"($arr := [1, 2, 3], $sum($arr))\"}";
        String result = SpecGenerator.fixBindingCommas(json);
        assertThat(result).contains("[1, 2, 3]");
        assertThat(result).contains("($arr := [1, 2, 3]; $sum($arr))");
    }

    @Test
    void fix_binding_commas_leaves_block_without_binding_unchanged() {
        // No := → commas must not be touched (could be a multi-arg function)
        String json = "{\"expr\":\"$fn(a, b, c)\"}";
        assertThat(SpecGenerator.fixBindingCommas(json)).isEqualTo(json);
    }

    @Test
    void fix_binding_commas_does_not_convert_object_literal_commas() {
        // Object literal {"a": $n, "b": $r} inside a binding block must keep its commas.
        // Only the outer block commas (between bindings) should become semicolons.
        String json = "\"($n := 10, $r := 0.05, {\\\"a\\\": $n, \\\"b\\\": $r})\"";
        assertThat(SpecGenerator.fixBindingCommas(json))
                .isEqualTo("\"($n := 10; $r := 0.05; {\\\"a\\\": $n, \\\"b\\\": $r})\"");
    }

    // ── fixFunctionBodyCommas ─────────────────────────────────────────────────

    @Test
    void fix_function_body_commas_converts_commas_to_semicolons_when_binding_present() {
        String json = "{\"expr\":\"$reduce([1..n], function($acc, $m) {$b := $acc + $m, $b}, [0])\"}";
        assertThat(SpecGenerator.fixFunctionBodyCommas(json))
                .isEqualTo("{\"expr\":\"$reduce([1..n], function($acc, $m) {$b := $acc + $m; $b}, [0])\"}");
    }

    @Test
    void fix_function_body_commas_leaves_object_literal_body_unchanged() {
        // No := at top level — this is a JSONata object literal body, commas must stay
        String json = "{\"expr\":\"$map(a, function($x) {\\\"v\\\": $x})\"}";
        assertThat(SpecGenerator.fixFunctionBodyCommas(json)).isEqualTo(json);
    }

    @Test
    void fix_function_body_commas_does_not_touch_nested_object_commas() {
        // Nested {"month": $m, "pay": mp} is inside $append() at parenD>0 — must keep commas
        String json = "{\"expr\":\"$reduce([1..n], function($acc, $m) {$b := $acc[-1].bal, $append($acc, {\\\"month\\\": $m, \\\"bal\\\": $b})}, [0])\"}";
        String result = SpecGenerator.fixFunctionBodyCommas(json);
        // Top-level comma converted, nested object comma kept
        assertThat(result).contains("{$b := $acc[-1].bal; $append($acc,");
        assertThat(result).contains("{\\\"month\\\": $m, \\\"bal\\\": $b}");
    }

    // ── fixObjectSemicolons ───────────────────────────────────────────────────

    @Test
    void fix_object_semicolons_converts_semicolons_in_object_literal() {
        // LLM generates {\"A\": 50; \"B\": 100} — semicolons must become commas
        String json = "\"($rates := {\\\"A\\\": 50; \\\"B\\\": 100}; $rates.A)\"";
        assertThat(SpecGenerator.fixObjectSemicolons(json))
                .isEqualTo("\"($rates := {\\\"A\\\": 50, \\\"B\\\": 100}; $rates.A)\"");
    }

    @Test
    void fix_object_semicolons_leaves_function_body_semicolons_unchanged() {
        // After fixFunctionSequenceBodies, function body is {(; ;)} — semicolons inside ()
        // fixObjectSemicolons must not convert those
        String json = "{\"expr\":\"$map(a, function($m) {($a := 1; $a + $m)})\"}";
        assertThat(SpecGenerator.fixObjectSemicolons(json)).isEqualTo(json);
    }

    @Test
    void fix_object_semicolons_leaves_binding_block_semicolons_unchanged() {
        // Semicolons in binding blocks are at parenDepth, not braceDepth — must be preserved
        String json = "{\"expr\":\"($a := 1; $b := 2; $a + $b)\"}";
        assertThat(SpecGenerator.fixObjectSemicolons(json)).isEqualTo(json);
    }

    @Test
    void fix_expressions_fixes_object_literal_semicolons_end_to_end() {
        // Full pipeline: object literal with semicolons → corrected to commas
        String json = "{\"expr\":\"($rates := {\\\"A\\\": 50; \\\"B\\\": 100}, $rates.A)\"}";
        String result = SpecGenerator.fixExpressions(json);
        assertThat(result).contains("{\\\"A\\\": 50, \\\"B\\\": 100}");
    }

    // ── fixExpressions: == equality operator ──────────────────────────────────

    @Test
    void fix_expressions_converts_double_equals_to_single() {
        // JSONata uses = for equality; LLMs write ==
        assertThat(SpecGenerator.fixExpressions("\"$x == 1\"")).isEqualTo("\"$x = 1\"");
    }

    @Test
    void fix_expressions_converts_triple_equals_to_single() {
        assertThat(SpecGenerator.fixExpressions("\"$x === 1\"")).isEqualTo("\"$x = 1\"");
    }

    @Test
    void fix_expressions_does_not_alter_assignment_operator() {
        // := must not be touched
        String s = "\"($x := 1; $x)\"";
        assertThat(SpecGenerator.fixExpressions(s)).contains(":=");
    }

    @Test
    void fix_expressions_does_not_alter_comparison_operators() {
        // <= and >= must not become < and >
        String s = "\"$x <= 10 and $x >= 0\"";
        assertThat(SpecGenerator.fixExpressions(s)).isEqualTo(s);
    }

    @Test
    void fix_expressions_does_not_alter_not_equal_operator() {
        // != must not be touched
        String s = "\"$x != 0\"";
        assertThat(SpecGenerator.fixExpressions(s)).isEqualTo(s);
    }

    @Test
    void fix_expressions_converts_strict_not_equal_to_not_equal() {
        // !== (JavaScript) → != (JSONata)
        assertThat(SpecGenerator.fixExpressions("\"$x !== 0\"")).isEqualTo("\"$x != 0\"");
    }

    // ── collapseStringNewlines ────────────────────────────────────────────────

    @Test
    void collapses_literal_newlines_inside_json_strings() {
        String json = "{\"expr\":\"\n  $a + $b\n\"}";
        assertThat(SpecGenerator.collapseStringNewlines(json))
                .isEqualTo("{\"expr\":\"   $a + $b \"}");
    }

    @Test
    void does_not_collapse_newlines_outside_strings() {
        String json = "{\n  \"expr\": \"$a\"\n}";
        assertThat(SpecGenerator.collapseStringNewlines(json))
                .isEqualTo("{\n  \"expr\": \"$a\"\n}");
    }

    @Test
    void handles_escaped_quote_inside_string() {
        // \\" is an escaped backslash followed by a real quote delimiter — must toggle inString
        String json = "{\"key\":\"val\\\\\"\n,\"k2\":\"v\"}";
        // The newline is outside the first string (after the escaped-backslash-then-close-quote)
        // so it must NOT be collapsed
        String result = SpecGenerator.collapseStringNewlines(json);
        assertThat(result).contains("\n"); // newline kept (it was outside a string value)
    }

    @Test
    void multiline_expression_becomes_parseable_json() throws Exception {
        String json = "{\"id\":\"m\",\"schema\":{},\"derivations\":[{\"path\":\"$.x\",\"expr\":\"\n  $a +\n  $b\n\"}]}";
        String collapsed = SpecGenerator.collapseStringNewlines(json);
        // Must parse without error after collapsing
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(collapsed);
    }

    // ── Successful generation on first try ────────────────────────────────────

    @Test
    void generate_succeeds_on_first_valid_response() {
        LlmClient stub = prompt -> """
                { "id": "order", "schema": {},
                  "derivations": [
                    { "path": "$.order.total", "expr": "order.sub + order.tax" }
                  ]
                }
                """;

        SpecGenerator gen    = new SpecGenerator(stub, MAPPER);
        var           result = gen.generate("order", "An order model with a computed total");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        var success = (GenerationResult.Success) result;
        assertThat(success.spec().id()).isEqualTo("order");
        assertThat(success.attemptsUsed()).isEqualTo(1);
    }

    // ── Retry on validation failure ────────────────────────────────────────────

    @Test
    void generate_retries_when_first_response_fails_validation() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient stub = prompt -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                // First response: invalid (blank id)
                return "{ \"id\": \"\", \"schema\": {} }";
            }
            // Second response: valid
            return "{ \"id\": \"loan\", \"schema\": {} }";
        };

        SpecGenerator gen    = new SpecGenerator(stub, MAPPER, 3);
        var           result = gen.generate("loan", "A loan application model");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        var success = (GenerationResult.Success) result;
        assertThat(success.attemptsUsed()).isEqualTo(2);
    }

    // ── Repair prompt contains error details ──────────────────────────────────

    @Test
    void repair_prompt_is_sent_on_second_attempt() {
        AtomicInteger calls = new AtomicInteger();
        StringBuilder secondPrompt = new StringBuilder();

        LlmClient stub = prompt -> {
            if (calls.incrementAndGet() == 1) return "{ \"id\": \"\", \"schema\": {} }";
            secondPrompt.append(prompt);
            return "{ \"id\": \"m\", \"schema\": {} }";
        };

        new SpecGenerator(stub, MAPPER, 2).generate("m", "desc");

        assertThat(secondPrompt.toString()).contains("validation errors");
        assertThat(secondPrompt.toString()).contains("id");
    }

    // ── Exhausted retries → Failure ────────────────────────────────────────────

    @Test
    void generate_returns_failure_after_all_retries_exhausted() {
        LlmClient stub = prompt -> "{ \"id\": \"\", \"schema\": {} }"; // always invalid

        SpecGenerator gen    = new SpecGenerator(stub, MAPPER, 2);
        var           result = gen.generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Failure.class);
        var failure = (GenerationResult.Failure) result;
        assertThat(failure.attemptsUsed()).isEqualTo(2);
        assertThat(failure.lastErrors()).isNotEmpty();
    }

    // ── Adaptive retry budget (hard specs) ──────────────────────────────────────

    /** Returns a spec whose derivation count = number of uncompilable ("(") expressions. */
    private static String specWithInvalidDerivations(int count) {
        StringBuilder d = new StringBuilder();
        for (int k = 0; k < count; k++) {
            if (k > 0) d.append(',');
            d.append("{\"path\":\"$.d").append(k).append("\",\"expr\":\"(\"}");
        }
        return "{\"id\":\"m\",\"schema\":{},\"derivations\":[" + d + "]}";
    }

    @Test
    void converging_hard_spec_gets_extra_attempts_beyond_base_budget() {
        // base=2, hard=2*2=4. Errors decrease 3→2→1→0, so the loop keeps going past the base
        // budget while converging and succeeds on the 4th attempt.
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient stub = prompt -> specWithInvalidDerivations(Math.max(0, 4 - n.incrementAndGet()));

        var result = new SpecGenerator(stub, MAPPER, 2).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(((GenerationResult.Success) result).attemptsUsed()).isEqualTo(4);
    }

    @Test
    void stuck_spec_stops_at_base_budget_no_extra_attempts() {
        // Constant error count (not converging) → no extra budget granted; stops at base=2.
        LlmClient stub = prompt -> specWithInvalidDerivations(2); // always 2 errors

        var result = new SpecGenerator(stub, MAPPER, 2).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Failure.class);
        assertThat(((GenerationResult.Failure) result).attemptsUsed()).isEqualTo(2);
    }

    // ── Derived fields marked read-only in schema ──────────────────────────────

    @Test
    void mark_derived_fields_readonly_flags_their_schema_properties() throws Exception {
        ModelSpec spec = MAPPER.readValue("""
                { "id": "m",
                  "schema": { "type": "object", "properties": {
                    "qty":   { "type": "integer" },
                    "total": { "type": "number" },
                    "order": { "type": "object", "properties": { "tax": { "type": "number" } } }
                  }},
                  "derivations": [
                    { "path": "$.total",     "expr": "qty * 2" },
                    { "path": "$.order.tax", "expr": "total * 0.1" }
                  ]
                }
                """, ModelSpec.class);

        var marked = SpecGenerator.markDerivedFieldsReadOnly(spec);

        assertThat(marked.schema().at("/properties/total/readOnly").asBoolean()).isTrue();
        assertThat(marked.schema().at("/properties/order/properties/tax/readOnly").asBoolean()).isTrue();
        // a genuine input field is left writable
        assertThat(marked.schema().at("/properties/qty/readOnly").isMissingNode()).isTrue();
    }

    @Test
    void evolution_marks_newly_derived_fields_readonly_in_new_schema() throws Exception {
        ModelSpec current = MAPPER.readValue("""
                { "id": "m", "schema": { "type": "object", "properties": { "qty": {"type":"integer"} } } }
                """, ModelSpec.class);
        // The evolution adds a derived "total" AND a newSchema that (wrongly) declares it writable.
        LlmClient stub = prompt -> """
                { "upsertDerivations": [ { "path": "$.total", "expr": "qty * 2" } ],
                  "newSchema": { "type": "object", "properties": {
                      "qty": {"type":"integer"}, "total": {"type":"number"} } } }
                """;

        org.json_kula.valem.core.graph.SpecEvolution evo =
                new SpecGenerator(stub, MAPPER, 2).generateEvolution(current, "add a total");

        assertThat(evo.newSchema().at("/properties/total/readOnly").asBoolean()).isTrue();
        assertThat(evo.newSchema().at("/properties/qty/readOnly").isMissingNode()).isTrue();
    }

    // ── Evolution parity with generate(): hints, exemplars, self-test verification ──

    @Test
    void evolution_prompt_lists_derived_paths_and_injects_shape_exemplar() {
        String p = SpecGenerationPrompt.evolutionPrompt(
                "m", "{}", "add an amortization schedule", false,
                java.util.List.of("$.total", "$.order.tax"));
        assertThat(p)
                .contains("already DERIVED")
                .contains("$.total, $.order.tax")
                .contains("one row per period"); // the schedule shape exemplar
    }

    @Test
    void evolution_repair_prompt_includes_feedback_previous_and_derived_paths() {
        String p = SpecGenerationPrompt.evolutionRepairPrompt(
                "m", "{\"id\":\"m\"}", "add a total", "{\"upsertDerivations\":[]}",
                "SOME RULE-NAMED FEEDBACK", false, java.util.List.of("$.total"));
        assertThat(p)
                .contains("SOME RULE-NAMED FEEDBACK")
                .contains("upsertDerivations")     // the previous evolution is echoed
                .contains("$.total")
                .contains("already DERIVED");
    }

    @Test
    void evolution_feeds_rule_named_hint_back_on_compile_error() throws Exception {
        ModelSpec current = MAPPER.readValue("""
                { "id": "m", "schema": { "type": "object", "properties": { "qty": {"type":"integer"} } } }
                """, ModelSpec.class);

        java.util.List<String> prompts = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient stub = prompt -> {
            prompts.add(prompt);
            // First attempt: an unbalanced-paren expr (no $, so the deterministic paren-balancer leaves
            // it broken) → applyTo's validation reports "Expected RPAREN". Second attempt: valid.
            return n.getAndIncrement() == 0
                    ? "{ \"upsertDerivations\": [ { \"path\": \"$.total\", \"expr\": \"(qty * 2\" } ] }"
                    : "{ \"upsertDerivations\": [ { \"path\": \"$.total\", \"expr\": \"qty * 2\" } ] }";
        };

        var evo = new SpecGenerator(stub, MAPPER, 3).generateEvolution(current, "add a total");

        assertThat(evo.upsertDerivations()).hasSize(1);
        // The second prompt must carry the rule-named RPAREN hint so the model converges.
        assertThat(prompts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(prompts.get(1)).contains("Count every");
    }

    @Test
    void evolution_returns_best_effort_when_merged_self_tests_fail() throws Exception {
        // Current spec has a self-test that passes today (qty 5 → total 10).
        ModelSpec current = MAPPER.readValue("""
                { "id": "m",
                  "schema": { "type": "object", "properties": { "qty": {"type":"integer"} } },
                  "derivations": [ { "path": "$.total", "expr": "qty * 2" } ],
                  "tests": [ { "given": { "$.qty": 5 }, "expect": { "$.total": 10 } } ] }
                """, ModelSpec.class);
        // The evolution rewrites the derivation to qty*3 → the carried self-test now fails (15 != 10).
        // Generation must still return the structurally-valid evolution (best-effort), not throw.
        LlmClient stub = prompt ->
                "{ \"upsertDerivations\": [ { \"path\": \"$.total\", \"expr\": \"qty * 3\" } ] }";

        var evo = new SpecGenerator(stub, MAPPER, 2).generateEvolution(current, "triple the total");

        assertThat(evo.upsertDerivations()).hasSize(1);
        assertThat(evo.upsertDerivations().get(0).expr()).isEqualTo("qty * 3");
    }

    // ── Embedded test-case request (self-verification) ──────────────────────────

    @Test
    void initial_prompt_requests_embedded_test_cases() {
        String p = SpecGenerationPrompt.initialPrompt("m", "a tax calculator");
        assertThat(p)
                .contains("\"tests\"")
                .contains("self-checks of your own math")
                .contains("compute each expected value yourself");
    }

    @Test
    void valid_spec_with_failing_embedded_test_is_returned_best_effort() {
        // The derivation computes 10 but the embedded test expects 99 — a failing self-test. The spec
        // is structurally valid, so generation must return it (best-effort), not fail outright.
        LlmClient stub = prompt -> """
                { "id": "m", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "qty * 2" } ],
                  "tests": [ { "given": { "$.qty": 5 }, "expect": { "$.total": 99 } } ] }
                """;

        var result = new SpecGenerator(stub, MAPPER, 2).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(((GenerationResult.Success) result).spec().derivations()).hasSize(1);
    }

    // ── Un-verifiable self-tests do not block generation (#3) ────────────────────

    @Test
    void array_valued_failing_self_test_does_not_block_and_is_not_retried() {
        // A whole-array assertion can't be hand-computed reliably; even though it fails, generation
        // must accept the structurally-valid spec on the FIRST attempt (no wasted retries).
        LlmClient stub = prompt -> """
                { "id": "m", "schema": {},
                  "derivations": [ { "path": "$.nums", "expr": "[1, 2, 3]" } ],
                  "tests": [ { "given": {}, "expect": { "$.nums": [9, 9, 9] } } ] }
                """;

        var result = new SpecGenerator(stub, MAPPER, 2).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(((GenerationResult.Success) result).attemptsUsed()).isEqualTo(1);
    }

    @Test
    void time_dependent_failing_self_test_does_not_block_and_is_not_retried() {
        // The derivation reads the current year via $now(); the hardcoded expectation (1999) will
        // never match at runtime, so it is not a reliable gate — accept on the first attempt.
        LlmClient stub = prompt -> """
                { "id": "m", "schema": {},
                  "derivations": [ { "path": "$.year", "expr": "$substring($now(), 0, 4)~>$number()" } ],
                  "tests": [ { "given": {}, "expect": { "$.year": 1999 } } ] }
                """;

        var result = new SpecGenerator(stub, MAPPER, 2).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(((GenerationResult.Success) result).attemptsUsed()).isEqualTo(1);
    }

    @Test
    void self_test_strict_rules_are_in_the_prompt() {
        String p = SpecGenerationPrompt.initialPrompt("m", "a calculator");
        assertThat(p)
                .contains("expect ONLY scalar")
                .contains("NEVER assert an")          // array/object rule
                .contains("$now()");                   // time-dependent rule
    }

    // ── Repair temperature ──────────────────────────────────────────────────────

    @Test
    void initial_attempt_uses_provider_default_temperature_and_repairs_use_low_temperature() {
        java.util.List<String> tempLog = new java.util.ArrayList<>();
        LlmClient stub = new LlmClient() {
            @Override public String complete(String prompt) {
                tempLog.add("default");
                return "{ \"id\": \"\", \"schema\": {} }";       // invalid → forces a repair attempt
            }
            @Override public String complete(String prompt, double temperature) {
                tempLog.add(String.valueOf(temperature));
                return "{ \"id\": \"m\", \"schema\": {} }";      // valid → success
            }
        };

        var result = new SpecGenerator(stub, MAPPER, 3).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(tempLog).containsExactly("default", "0.2"); // first attempt default, repair low
    }

    @Test
    void repair_temperature_is_configurable() {
        java.util.List<String> tempLog = new java.util.ArrayList<>();
        LlmClient stub = new LlmClient() {
            @Override public String complete(String prompt) {
                tempLog.add("default"); return "{ \"id\": \"\", \"schema\": {} }";
            }
            @Override public String complete(String prompt, double temperature) {
                tempLog.add(String.valueOf(temperature)); return "{ \"id\": \"m\", \"schema\": {} }";
            }
        };

        // 6-arg ctor: maxRetries=3, hard=6, repairTemperature=0.05
        var result = new SpecGenerator(stub, MAPPER, 3, 6, 0.05, null).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(tempLog).containsExactly("default", "0.05");
    }

    // ── Generation temperature (initial attempt) ────────────────────────────────

    @Test
    void generation_temperature_is_applied_on_the_initial_attempt() {
        java.util.List<String> tempLog = new java.util.ArrayList<>();
        LlmClient stub = new LlmClient() {
            @Override public String complete(String prompt) {
                tempLog.add("default"); return "{ \"id\": \"m\", \"schema\": {} }";
            }
            @Override public String complete(String prompt, double temperature) {
                tempLog.add(String.valueOf(temperature)); return "{ \"id\": \"m\", \"schema\": {} }";
            }
        };
        // new ctor: generationTemperature=0.0, structuredOutput=false
        var result = new SpecGenerator(stub, MAPPER, 3, 6, 0.2, 0.0, false, null).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(tempLog).containsExactly("0.0"); // initial attempt used the generation temperature
    }

    // ── Structured output (provider response schema) ────────────────────────────

    @Test
    void structured_output_passes_the_model_spec_schema_to_the_client() {
        java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        LlmClient stub = new LlmClient() {
            @Override public String complete(String prompt) { return "{ \"id\": \"m\", \"schema\": {} }"; }
            @Override public String complete(String prompt, LlmClient.CompletionOptions options) {
                captured.set(options.responseSchema());
                return "{ \"id\": \"m\", \"schema\": {} }";
            }
        };
        var result = new SpecGenerator(stub, MAPPER, 3, 6, 0.2, 0.0, true, null).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().at("/properties/derivations").isMissingNode()).isFalse();
    }

    @Test
    void structured_output_disabled_passes_no_schema() {
        java.util.concurrent.atomic.AtomicReference<Boolean> schemaWasNull =
                new java.util.concurrent.atomic.AtomicReference<>(false);
        LlmClient stub = new LlmClient() {
            @Override public String complete(String prompt) { return "{ \"id\": \"m\", \"schema\": {} }"; }
            @Override public String complete(String prompt, LlmClient.CompletionOptions options) {
                schemaWasNull.set(options.responseSchema() == null);
                return "{ \"id\": \"m\", \"schema\": {} }";
            }
        };
        new SpecGenerator(stub, MAPPER, 3, 6, 0.2, 0.0, false, null).generate("m", "desc");

        assertThat(schemaWasNull.get()).isTrue();
    }

    @Test
    void initial_prompt_contains_a_complete_worked_example() {
        String p = SpecGenerationPrompt.initialPrompt("m", "a tax calculator");
        assertThat(p)
                .contains("COMPLETE EXAMPLE")
                .contains("rectangle")
                .contains("area = width x height");
    }

    // ── Shape-based few-shot exemplars ──────────────────────────────────────────

    @Test
    void shape_exemplar_injected_for_schedule_domains() {
        assertThat(SpecGenerationPrompt.shapeExemplars("A loan with an amortization schedule"))
                .contains("one row per period").contains("$reduce(");
        assertThat(SpecGenerationPrompt.initialPrompt("m", "show a monthly payment breakdown table"))
                .contains("one row per period");
        // "each month" enumeration phrasing (the CarLoan IT wording) must trigger it too.
        assertThat(SpecGenerationPrompt.shapeExemplars(
                "car loan calculator with details on each month and total period"))
                .contains("one row per period");
    }

    // ── Embedded-test feedback hints ────────────────────────────────────────────

    @Test
    void test_failure_hint_null_result_points_at_field_names() {
        var f = new org.json_kula.valem.core.engine.TestCaseRunner.FieldFailure(
                "$.x", MAPPER.valueToTree(5), MAPPER.nullNode(), "msg");
        assertThat(SpecGenerationPrompt.testFailureHint(f))
                .contains("returned null").contains("field name");
    }

    @Test
    void test_failure_hint_tiny_numeric_delta_suggests_round() {
        var f = new org.json_kula.valem.core.engine.TestCaseRunner.FieldFailure(
                "$.x", MAPPER.valueToTree(100.0), MAPPER.valueToTree(100.00001), "msg");
        assertThat(SpecGenerationPrompt.testFailureHint(f)).contains("$round");
    }

    @Test
    void test_failure_hint_large_numeric_diff_mentions_precedence() {
        var f = new org.json_kula.valem.core.engine.TestCaseRunner.FieldFailure(
                "$.x", MAPPER.valueToTree(100.0), MAPPER.valueToTree(50.0), "msg");
        assertThat(SpecGenerationPrompt.testFailureHint(f)).contains("precedence");
    }

    @Test
    void shape_exemplar_for_group_by_domains() {
        assertThat(SpecGenerationPrompt.shapeExemplars("sum expenses grouped by category"))
                .contains("GROUP-BY").contains("items{category: $sum(amount)}");
    }

    @Test
    void shape_exemplar_for_date_arithmetic_domains() {
        assertThat(SpecGenerationPrompt.shapeExemplars("compute the number of days between two dates"))
                .contains("DATE ARITHMETIC").contains("$toMillis");
    }

    @Test
    void shape_exemplar_for_classification_domains() {
        assertThat(SpecGenerationPrompt.shapeExemplars("classify customers into a risk level"))
                .contains("DERIVES a label").contains("nested ternary");
    }

    @Test
    void shape_exemplar_for_currency_fx_domains() {
        assertThat(SpecGenerationPrompt.shapeExemplars("convert amounts using an exchange rate"))
                .contains("CURRENCY / FX CONVERSION").contains("$round(amount * exchangeRate, 2)");
    }

    @Test
    void shape_exemplar_for_status_state_domains() {
        assertThat(SpecGenerationPrompt.shapeExemplars("an order status with an approval workflow"))
                .contains("STATUS / STATE").contains("CANNOT validate transitions");
    }

    @Test
    void shape_exemplar_for_rank_percentile_domains() {
        assertThat(SpecGenerationPrompt.shapeExemplars("compute each student's percentile and rank"))
                .contains("RANK / PERCENTILE").contains("$count(");
    }

    @Test
    void shape_exemplar_absent_for_ordinary_domains() {
        assertThat(SpecGenerationPrompt.shapeExemplars("A simple sales tax calculator")).isEmpty();
        assertThat(SpecGenerationPrompt.initialPrompt("m", "a simple counter"))
                .doesNotContain("one row per period");
    }

    @Test
    void test_repair_prompt_quotes_the_offending_derivation_expr() throws Exception {
        var failures = java.util.List.of(new org.json_kula.valem.core.engine.TestCaseRunner.FieldFailure(
                "$.total", MAPPER.valueToTree(100), MAPPER.valueToTree(90), "$.total expected 100 but was 90"));
        var failed = java.util.List.of(
                new org.json_kula.valem.core.engine.TestCaseRunner.TestResult("t1", false, failures));
        var derivations = java.util.List.of(
                org.json_kula.valem.core.model.DerivationSpec.of(
                        "$.total", "subtotal + tax", null, null));

        String prompt = SpecGenerationPrompt.testRepairPrompt("m", "{}", failed, derivations);

        assertThat(prompt).contains("derivation at $.total is: subtotal + tax");
    }

    // ── Malformed JSON → repair prompt ────────────────────────────────────────

    @Test
    void generate_retries_on_malformed_json() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient stub = prompt -> {
            if (calls.incrementAndGet() == 1) return "NOT JSON AT ALL";
            return "{ \"id\": \"m\", \"schema\": {} }";
        };

        SpecGenerator gen    = new SpecGenerator(stub, MAPPER, 3);
        var           result = gen.generate("m", "some model");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
    }

    // ── Test case repair loop ─────────────────────────────────────────────────

    @Test
    void generate_retries_when_embedded_tests_fail() {
        AtomicInteger calls = new AtomicInteger();
        StringBuilder secondPrompt = new StringBuilder();

        LlmClient stub = prompt -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                // Wrong expression — subtraction instead of addition
                return """
                        {
                          "id": "m", "schema": {},
                          "derivations": [{ "path": "$.x.total", "expr": "x.a - x.b" }],
                          "tests": [
                            { "description": "sum", "given": {"$.x.a": 10, "$.x.b": 5}, "expect": {"$.x.total": 15} }
                          ]
                        }
                        """;
            }
            secondPrompt.append(prompt);
            // Fixed expression
            return """
                    {
                      "id": "m", "schema": {},
                      "derivations": [{ "path": "$.x.total", "expr": "x.a + x.b" }],
                      "tests": [
                        { "description": "sum", "given": {"$.x.a": 10, "$.x.b": 5}, "expect": {"$.x.total": 15} }
                      ]
                    }
                    """;
        };

        var result = new SpecGenerator(stub, MAPPER, 3).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(secondPrompt.toString()).contains("test cases failed");
        assertThat(secondPrompt.toString()).contains("sum");
    }

    @Test
    void generate_succeeds_immediately_when_tests_pass() {
        LlmClient stub = prompt -> """
                {
                  "id": "m", "schema": {},
                  "derivations": [{ "path": "$.x.total", "expr": "x.a + x.b" }],
                  "tests": [
                    { "description": "sum", "given": {"$.x.a": 10, "$.x.b": 5}, "expect": {"$.x.total": 15} }
                  ]
                }
                """;

        var result = new SpecGenerator(stub, MAPPER, 3).generate("m", "desc");

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        assertThat(((GenerationResult.Success) result).attemptsUsed()).isEqualTo(1);
    }

    // ── Prompt content ────────────────────────────────────────────────────────

    @Test
    void initial_prompt_contains_model_id_and_domain() {
        String prompt = SpecGenerationPrompt.initialPrompt("invoice", "An invoice management model");
        assertThat(prompt).contains("Model ID: invoice");
        assertThat(prompt).contains("An invoice management model");
        assertThat(prompt).contains("JSONata");
    }

    @Test
    void initial_prompt_asks_the_llm_to_choose_an_id_when_none_is_given() {
        // A blank model id (the sandbox sends this when the user provides no name) must make the
        // prompt instruct the LLM to pick its own id, rather than emitting a blank "Model ID:".
        for (String blank : new String[]{null, "", "   "}) {
            String prompt = SpecGenerationPrompt.initialPrompt(blank, "A mortgage calculator");
            assertThat(prompt).contains("choose one yourself");
            assertThat(prompt).contains("kebab-case");
            assertThat(prompt).doesNotContain("Model ID: \n");
            assertThat(prompt).contains("A mortgage calculator");
        }
    }

    @Test
    void repair_prompt_contains_errors_and_previous_spec() {
        var errors = java.util.List.of(
                new ModelSpecValidator.ValidationError("id", "Model id is required", ModelSpecValidator.Severity.ERROR));
        String prompt = SpecGenerationPrompt.repairPrompt("m", "{\"id\":\"\"}", errors);
        assertThat(prompt).contains("Model id is required");
        assertThat(prompt).contains("{\"id\":\"\"}");
    }

    // ── repairConstraintPolicy ────────────────────────────────────────────────

    @Test
    void repair_constraint_policy_adds_rollback_when_missing() {
        String json = "{\"constraints\":[{\"id\":\"c1\",\"expr\":\"x > 0\",\"message\":\"must be positive\"}]}";
        String fixed = SpecGenerator.repairConstraintPolicy(json, MAPPER);
        assertThat(fixed).contains("\"policy\":\"rollback\"");
    }

    @Test
    void repair_constraint_policy_leaves_existing_policy_unchanged() {
        String json = "{\"constraints\":[{\"id\":\"c1\",\"expr\":\"x > 0\",\"message\":\"msg\",\"policy\":\"flag\"}]}";
        String fixed = SpecGenerator.repairConstraintPolicy(json, MAPPER);
        assertThat(fixed).contains("\"policy\":\"flag\"");
        assertThat(fixed).doesNotContain("rollback");
    }

    @Test
    void repair_constraint_policy_handles_multiple_constraints() {
        String json = "{\"constraints\":[" +
                "{\"id\":\"c1\",\"expr\":\"a > 0\",\"message\":\"a\"}," +
                "{\"id\":\"c2\",\"expr\":\"b > 0\",\"message\":\"b\",\"policy\":\"flag\"}," +
                "{\"id\":\"c3\",\"expr\":\"c > 0\",\"message\":\"c\"}" +
                "]}";
        String fixed = SpecGenerator.repairConstraintPolicy(json, MAPPER);
        assertThat(fixed).contains("\"id\":\"c1\"");
        assertThat(fixed).contains("\"id\":\"c3\"");
        // c1 and c3 get rollback; c2 keeps flag
        long rollbackCount = java.util.regex.Pattern.compile("rollback").matcher(fixed).results().count();
        assertThat(rollbackCount).isEqualTo(2);
        assertThat(fixed).contains("\"policy\":\"flag\"");
    }

    @Test
    void repair_constraint_policy_returns_unchanged_on_malformed_json() {
        String json = "NOT JSON";
        assertThat(SpecGenerator.repairConstraintPolicy(json, MAPPER)).isEqualTo("NOT JSON");
    }

    // ── annotateErrors ────────────────────────────────────────────────────────

    @Test
    void annotate_errors_adds_reduce_hint_for_already_defined_error() {
        var errors = java.util.List.of(
                new ModelSpecValidator.ValidationError(
                        "derivations[2].expr",
                        "Invalid JSONata expression — variable $balance is already defined in method __block2",
                        ModelSpecValidator.Severity.ERROR));
        var annotated = SpecGenerator.annotateErrors(errors);
        assertThat(annotated).hasSize(1);
        assertThat(annotated.get(0).message()).contains("$reduce");
        assertThat(annotated.get(0).message()).contains("immutable");
        assertThat(annotated.get(0).location()).isEqualTo("derivations[2].expr");
    }

    @Test
    void annotate_errors_leaves_unrelated_errors_unchanged() {
        var errors = java.util.List.of(
                new ModelSpecValidator.ValidationError(
                        "id", "Model id is required", ModelSpecValidator.Severity.ERROR));
        var annotated = SpecGenerator.annotateErrors(errors);
        assertThat(annotated).hasSize(1);
        assertThat(annotated.get(0).message()).isEqualTo("Model id is required");
    }
}
