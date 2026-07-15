package org.json_kula.valem.core.graph;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionPathExtractorTest {

    // ── Simple field paths ────────────────────────────────────────────────────

    @Test
    void bare_field_ref() {
        assertThat(extract("price")).containsExactlyInAnyOrder("$.price");
    }

    @Test
    void dot_path() {
        assertThat(extract("order.total")).containsExactlyInAnyOrder("$.order.total");
    }

    @Test
    void deep_dot_path() {
        assertThat(extract("order.customer.creditLimit"))
                .containsExactlyInAnyOrder("$.order.customer.creditLimit");
    }

    // ── Binary ops ────────────────────────────────────────────────────────────

    @Test
    void addition_two_paths() {
        assertThat(extract("order.subtotal + order.tax"))
                .containsExactlyInAnyOrder("$.order.subtotal", "$.order.tax");
    }

    @Test
    void comparison_two_paths() {
        assertThat(extract("order.total <= customer.creditLimit"))
                .containsExactlyInAnyOrder("$.order.total", "$.customer.creditLimit");
    }

    // ── Ternary / conditional ─────────────────────────────────────────────────

    @Test
    void ternary_reads_condition_and_both_branches() {
        Set<String> paths = extract(
                "applicant.maritalStatus = 'married' ? applicant.income + spouse.income : applicant.income");
        assertThat(paths).containsExactlyInAnyOrder(
                "$.applicant.maritalStatus", "$.applicant.income", "$.spouse.income");
    }

    // ── Function calls ────────────────────────────────────────────────────────

    @Test
    void function_call_single_arg() {
        assertThat(extract("$string(order.status)")).containsExactlyInAnyOrder("$.order.status");
    }

    @Test
    void sum_of_flat_array_field() {
        // $sum(order.items.qty) — items is an array; JSONata maps qty over it
        assertThat(extract("$sum(order.items.qty)")).containsExactlyInAnyOrder("$.order.items.qty");
    }

    // ── Array wildcard [*] ────────────────────────────────────────────────────

    @Test
    void predicate_wildcard_produces_array_path() {
        // order.items[*] — select all elements
        Set<String> paths = extract("order.items[*]");
        // Parser may emit this as PredicateExpr(FieldRef("items"), WildcardStep()) in a PathExpr,
        // or as PathExpr([FieldRef("order"), FieldRef("items"), WildcardStep()])
        // Either way the result must contain a path covering order.items[*]
        assertThat(paths).anyMatch(p -> p.startsWith("$.order.items"));
    }

    // ── Map expression (parenthesised block inside path) ─────────────────────

    @Test
    void map_expression_produces_array_element_paths() {
        // $sum(order.items.(qty * unitPrice))
        // The sub-expression (qty * unitPrice) maps over order.items elements
        Set<String> paths = extract("$sum(order.items.(qty * unitPrice))");
        assertThat(paths)
                .contains("$.order.items[*].qty", "$.order.items[*].unitPrice");
    }

    // ── Predicate filter on array ─────────────────────────────────────────────

    @Test
    void predicate_filter_emits_source_and_predicate_fields() {
        // order.items[status = "active"] — depends on items and status
        Set<String> paths = extract("order.items[status = 'active']");
        // Must contain a reference to order.items (or order.items[*])
        assertThat(paths).anyMatch(p -> p.startsWith("$.order.items"));
        // Must also capture "status" as a field read in the predicate
        assertThat(paths).anyMatch(p -> p.contains("status"));
    }

    // ── Nested paths ──────────────────────────────────────────────────────────

    @Test
    void multiple_independent_paths_in_object_constructor() {
        Set<String> paths = extract("{ 'total': order.total, 'name': customer.name }");
        assertThat(paths).containsExactlyInAnyOrder("$.order.total", "$.customer.name");
    }

    // ── $parent variable (wildcard derivation context) ───────────────────────

    @Test
    void parent_field_ref_resolves_relative_to_parent_prefix() {
        // $parent.price * $parent.qty in a derivation at $.items[*].lineTotal
        Set<String> paths = extract("$parent.price * $parent.qty", "$.items[*]");
        assertThat(paths).containsExactlyInAnyOrder("$.items[*].price", "$.items[*].qty");
    }

    @Test
    void parent_nested_field_resolves_under_parent_prefix() {
        // $parent.address.city in a derivation at $.users[*].city
        Set<String> paths = extract("$parent.address.city", "$.users[*]");
        assertThat(paths).containsExactlyInAnyOrder("$.users[*].address.city");
    }

    @Test
    void parent_in_function_call_resolves_correctly() {
        // $string($parent.code) in a derivation at $.items[*].codeStr
        Set<String> paths = extract("$string($parent.code)", "$.items[*]");
        assertThat(paths).containsExactlyInAnyOrder("$.items[*].code");
    }

    @Test
    void non_parent_variable_ref_still_produces_no_field_path() {
        // $myVar is a runtime variable unrelated to $parent
        assertThat(extract("$myVar")).isEmpty();
        assertThat(extract("$myVar", "$.items[*]")).isEmpty();
    }

    @Test
    void parent_without_field_access_produces_no_path() {
        // $parent alone (whole element reference) — no specific field dependency
        assertThat(extract("$parent", "$.items[*]")).isEmpty();
    }

    // ── Literals and variables produce no paths ───────────────────────────────

    @Test
    void literal_expression_produces_no_paths() {
        assertThat(extract("42")).isEmpty();
        assertThat(extract("'hello'")).isEmpty();
        assertThat(extract("true")).isEmpty();
        assertThat(extract("null")).isEmpty();
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void invalid_expression_throws_illegal_argument() {
        assertThatThrownBy(() -> extract("order.items[unclosed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot parse JSONata expression");
    }

    // ── Deduplication ─────────────────────────────────────────────────────────

    @Test
    void duplicate_paths_are_deduplicated() {
        // Both branches of the ternary read order.total
        Set<String> paths = extract("order.total > 0 ? order.total : order.total");
        assertThat(paths).containsExactlyInAnyOrder("$.order.total");
    }

    // ── Spec examples ─────────────────────────────────────────────────────────

    @Test
    void spec_example_total_derivation() {
        // order.total = order.subtotal + order.tax  (meta-derivation expr only)
        assertThat(extract("order.subtotal + order.tax"))
                .containsExactlyInAnyOrder("$.order.subtotal", "$.order.tax");
    }

    @Test
    void spec_example_min_payment_meta_derivation() {
        // minimum for order.downPayment: order.total * 0.2
        assertThat(extract("order.total * 0.2")).containsExactlyInAnyOrder("$.order.total");
    }

    @Test
    void spec_example_credit_constraint() {
        // order.total <= customer.creditLimit
        assertThat(extract("order.total <= customer.creditLimit"))
                .containsExactlyInAnyOrder("$.order.total", "$.customer.creditLimit");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Set<String> extract(String expr) {
        return ExpressionPathExtractor.extract(expr);
    }

    private Set<String> extract(String expr, String parentPrefix) {
        return ExpressionPathExtractor.extract(expr, parentPrefix);
    }
}
