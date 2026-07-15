package org.json_kula.valem.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration tests exercising the full reactive pipeline through ModelRuntime
 * using realistic domain scenarios: order processing, shopping cart, loan application.
 */
class IntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF   = JsonNodeFactory.instance;

    // ── Order processing ──────────────────────────────────────────────────────────

    @Test
    void order_total_and_vat_derive_from_base_fields() throws Exception {
        // Two independent derivations both depending only on base fields
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "derivations": [
                { "path": "$.order.total",  "expr": "order.subtotal + order.tax" },
                { "path": "$.order.vat",    "expr": "order.tax / (order.subtotal + order.tax)" }
              ]
            }
            """);

        rt.mutate(Map.of(
            "$.order.subtotal", NF.numberNode(200.0),
            "$.order.tax",      NF.numberNode(20.0)));

        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(220.0);
        assertThat(rt.getValue("$.order.vat").asDouble()).isCloseTo(0.0909, within(0.0001));
    }

    @Test
    void order_total_reacts_to_each_incremental_mutation() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        rt.mutate("$.order.subtotal", NF.numberNode(100.0));
        rt.mutate("$.order.tax",      NF.numberNode(0.0));
        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(100.0);

        rt.mutate("$.order.tax", NF.numberNode(18.0));
        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(118.0);

        rt.mutate("$.order.subtotal", NF.numberNode(200.0));
        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(218.0);
    }

    @Test
    void order_credit_limit_rollback_restores_full_pre_mutation_state() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ],
              "constraints": [
                { "id": "credit-check",
                  "expr": "order.total <= customer.creditLimit",
                  "message": "Exceeds credit limit",
                  "policy": "rollback" }
              ]
            }
            """);

        rt.mutate(Map.of(
            "$.customer.creditLimit", NF.numberNode(500.0),
            "$.order.subtotal",       NF.numberNode(100.0),
            "$.order.tax",            NF.numberNode(10.0)));

        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(110.0);

        // Push subtotal over the credit limit
        assertThatThrownBy(() -> rt.mutate("$.order.subtotal", NF.numberNode(600.0)))
            .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class)
            .hasMessageContaining("Exceeds credit limit");

        // State is fully rolled back
        assertThat(rt.getValue("$.order.subtotal").asDouble()).isEqualTo(100.0);
        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(110.0);
    }

    @Test
    void large_order_caller_effect_fires_with_computed_payload() throws Exception {
        List<EffectRequest> fired = new ArrayList<>();
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ],
              "effects": [
                { "id": "large-order-alert", "executor": "caller",
                  "trigger": "order.total > 500",
                  "emit": "order.large",
                  "payload": { "total": "order.total", "customer": "order.customer" } }
              ]
            }
            """);
        rt.setEffectSink(fired::add);

        rt.mutate(Map.of(
            "$.order.subtotal", NF.numberNode(450.0),
            "$.order.tax",      NF.numberNode(100.0),
            "$.order.customer", NF.textNode("acme")));

        assertThat(fired).hasSize(1);
        EffectRequest.Caller c = (EffectRequest.Caller) fired.getFirst();
        assertThat(c.emit()).isEqualTo("order.large");
        assertThat(c.payload().get("total").asDouble()).isEqualTo(550.0);
        assertThat(c.payload().get("customer").asText()).isEqualTo("acme");
    }

    @Test
    void caller_effect_does_not_fire_when_trigger_condition_is_false() throws Exception {
        List<EffectRequest> fired = new ArrayList<>();
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "effects": [
                { "id": "alert", "executor": "caller",
                  "trigger": "order.amount > 1000",
                  "emit": "order.high-value",
                  "payload": {} }
              ]
            }
            """);
        rt.setEffectSink(fired::add);

        rt.mutate("$.order.amount", NF.numberNode(300.0));

        assertThat(fired).isEmpty();
    }

    // ── Shopping cart ─────────────────────────────────────────────────────────────

    @Test
    void cart_total_is_sum_of_line_items() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "cart", "schema": {},
              "derivations": [
                { "path": "$.cart.total", "expr": "$sum(cart.items.(qty * unitPrice))" }
              ]
            }
            """);

        rt.mutate(Map.of(
            "$.cart.items[0].qty",       NF.numberNode(2),
            "$.cart.items[0].unitPrice", NF.numberNode(15.0),
            "$.cart.items[1].qty",       NF.numberNode(3),
            "$.cart.items[1].unitPrice", NF.numberNode(10.0)));

        // (2 × 15) + (3 × 10) = 30 + 30 = 60
        assertThat(rt.getValue("$.cart.total").asDouble()).isEqualTo(60.0);
    }

    @Test
    void cart_total_reacts_when_item_quantity_changes() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "cart", "schema": {},
              "derivations": [
                { "path": "$.cart.total", "expr": "$sum(cart.items.(qty * unitPrice))" }
              ]
            }
            """);

        rt.mutate(Map.of(
            "$.cart.items[0].qty",       NF.numberNode(1),
            "$.cart.items[0].unitPrice", NF.numberNode(50.0)));
        assertThat(rt.getValue("$.cart.total").asDouble()).isEqualTo(50.0);

        rt.mutate("$.cart.items[0].qty", NF.numberNode(4));
        assertThat(rt.getValue("$.cart.total").asDouble()).isEqualTo(200.0);
    }

    @Test
    void cart_empty_check_flags_but_does_not_rollback() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "cart", "schema": {},
              "derivations": [
                { "path": "$.cart.total", "expr": "$sum(cart.items.(qty * unitPrice))" }
              ],
              "constraints": [
                { "id": "positive-total",
                  "expr": "cart.total > 0",
                  "message": "Cart total must be positive",
                  "policy": "flag" }
              ]
            }
            """);

        var result = rt.mutate(Map.of(
            "$.cart.items[0].qty",       NF.numberNode(0),
            "$.cart.items[0].unitPrice", NF.numberNode(50.0)));

        // FLAG policy: state is committed but violation reported
        assertThat(result.success()).isTrue();
        assertThat(result.hasFlags()).isTrue();
        assertThat(result.flaggedConstraints().getFirst().constraintId()).isEqualTo("positive-total");
        assertThat(rt.getValue("$.cart.total").asDouble()).isEqualTo(0.0);
    }

    @Test
    void cart_items_have_per_item_line_total_derived_field() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "cart", "schema": {},
              "derivations": [
                { "path": "$.cart.total",              "expr": "$sum(cart.items.(qty * unitPrice))" },
                { "path": "$.cart.items[*].lineTotal", "expr": "$parent.qty * $parent.unitPrice" }
              ]
            }
            """);

        rt.mutate(Map.of(
            "$.cart.items[0].qty",       NF.numberNode(2),
            "$.cart.items[0].unitPrice", NF.numberNode(15.0),
            "$.cart.items[1].qty",       NF.numberNode(3),
            "$.cart.items[1].unitPrice", NF.numberNode(10.0)));

        assertThat(rt.getValue("$.cart.items[0].lineTotal").asDouble()).isEqualTo(30.0); // 2*15
        assertThat(rt.getValue("$.cart.items[1].lineTotal").asDouble()).isEqualTo(30.0); // 3*10
        assertThat(rt.getValue("$.cart.total").asDouble()).isEqualTo(60.0);

        // lineTotal reacts to qty change
        rt.mutate("$.cart.items[0].qty", NF.numberNode(1));
        assertThat(rt.getValue("$.cart.items[0].lineTotal").asDouble()).isEqualTo(15.0); // 1*15
        assertThat(rt.getValue("$.cart.total").asDouble()).isEqualTo(45.0);
    }

    // ── Loan application ──────────────────────────────────────────────────────────

    @Test
    void loan_monthly_payment_derived_from_principal_and_term() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "loan", "schema": {},
              "derivations": [
                { "path": "$.loan.monthlyPayment", "expr": "loan.principal / loan.termMonths" }
              ]
            }
            """);

        rt.mutate(Map.of(
            "$.loan.principal",  NF.numberNode(120000.0),
            "$.loan.termMonths", NF.numberNode(360.0)));

        assertThat(rt.getValue("$.loan.monthlyPayment").asDouble())
            .isCloseTo(333.33, within(0.01));
    }

    @Test
    void loan_dti_constraint_rejects_excessive_principal() throws Exception {
        // DTI = (principal / termMonths) / monthlyIncome — computed inline, no derivation chain
        ModelRuntime rt = runtime("""
            {
              "id": "loan", "schema": {},
              "derivations": [
                { "path": "$.loan.dti",
                  "expr": "(loan.principal / loan.termMonths) / applicant.monthlyIncome" }
              ],
              "constraints": [
                { "id": "dti-check",
                  "expr": "loan.dti <= 0.43",
                  "message": "Debt-to-income ratio exceeds 43%%",
                  "policy": "rollback" }
              ]
            }
            """);

        // Acceptable loan: payment = 50000/60 = 833/mo, income = 5000 → DTI = 0.17
        rt.mutate(Map.of(
            "$.loan.principal",         NF.numberNode(50000.0),
            "$.loan.termMonths",        NF.numberNode(60.0),
            "$.applicant.monthlyIncome",NF.numberNode(5000.0)));
        assertThat(rt.getValue("$.loan.dti").asDouble()).isLessThan(0.43);

        // Unaffordable: payment = 200000/24 = 8333/mo, income = 5000 → DTI = 1.67
        assertThatThrownBy(() -> rt.mutate(Map.of(
                "$.loan.principal",  NF.numberNode(200000.0),
                "$.loan.termMonths", NF.numberNode(24.0))))
            .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);

        // State rolled back to the previous acceptable values
        assertThat(rt.getValue("$.loan.principal").asDouble()).isEqualTo(50000.0);
        assertThat(rt.getValue("$.loan.dti").asDouble()).isLessThan(0.43);
    }

    @Test
    void loan_meta_derivation_sets_dynamic_maximum_principal() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "loan", "schema": {},
              "metaDerivations": [
                { "path": "$.loan.principal",
                  "property": "maximum",
                  "expr": "applicant.monthlyIncome * 12 * 5" }
              ]
            }
            """);

        rt.mutate("$.applicant.monthlyIncome", NF.numberNode(5000.0));

        // maximum = 5000 * 12 * 5 = 300,000
        var schema = rt.effectiveSchema("$.loan.principal");
        assertThat(schema.get("maximum").asDouble()).isEqualTo(300_000.0);
    }

    @Test
    void loan_meta_derivation_updates_when_income_changes() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "loan", "schema": {},
              "metaDerivations": [
                { "path": "$.loan.principal",
                  "property": "maximum",
                  "expr": "applicant.monthlyIncome * 12 * 5" }
              ]
            }
            """);

        rt.mutate("$.applicant.monthlyIncome", NF.numberNode(3000.0));
        assertThat(rt.effectiveSchema("$.loan.principal").get("maximum").asDouble())
            .isEqualTo(180_000.0);

        rt.mutate("$.applicant.monthlyIncome", NF.numberNode(8000.0));
        assertThat(rt.effectiveSchema("$.loan.principal").get("maximum").asDouble())
            .isEqualTo(480_000.0);
    }

    // ── Full pipeline ─────────────────────────────────────────────────────────────

    @Test
    void full_pipeline_derivation_constraint_effect_in_single_model() throws Exception {
        List<EffectRequest> fired = new ArrayList<>();
        ModelRuntime rt = runtime("""
            {
              "id": "invoice", "schema": {},
              "derivations": [
                { "path": "$.invoice.net",   "expr": "invoice.subtotal - invoice.discount" },
                { "path": "$.invoice.gross",  "expr": "invoice.subtotal - invoice.discount + invoice.tax" }
              ],
              "constraints": [
                { "id": "discount-cap",
                  "expr": "invoice.discount <= invoice.subtotal * 0.5",
                  "message": "Discount exceeds 50%% of subtotal",
                  "policy": "rollback" },
                { "id": "positive-gross",
                  "expr": "invoice.gross > 0",
                  "message": "Gross must be positive",
                  "policy": "flag" }
              ],
              "effects": [
                { "id": "invoice-ready", "executor": "caller",
                  "trigger": "invoice.gross > 0",
                  "emit": "invoice.ready",
                  "payload": { "gross": "invoice.gross" } }
              ]
            }
            """);
        rt.setEffectSink(fired::add);

        rt.mutate(Map.of(
            "$.invoice.subtotal",  NF.numberNode(1000.0),
            "$.invoice.discount",  NF.numberNode(100.0),
            "$.invoice.tax",       NF.numberNode(90.0)));

        // Derivations settled: net=900, gross=990
        assertThat(rt.getValue("$.invoice.net").asDouble()).isEqualTo(900.0);
        assertThat(rt.getValue("$.invoice.gross").asDouble()).isEqualTo(990.0);

        // No constraint violations
        var result = rt.mutate("$.invoice.tax", NF.numberNode(90.0)); // no-op re-mutation
        assertThat(result.hasFlags()).isFalse();

        // Caller effect fired because gross > 0
        assertThat(fired).hasSizeGreaterThanOrEqualTo(1);
        EffectRequest.Caller c = (EffectRequest.Caller) fired.getFirst();
        assertThat(c.emit()).isEqualTo("invoice.ready");
        assertThat(c.payload().get("gross").asDouble()).isEqualTo(990.0);

        // Excessive discount blocked by rollback constraint
        assertThatThrownBy(() -> rt.mutate("$.invoice.discount", NF.numberNode(600.0)))
            .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class)
            .hasMessageContaining("Discount exceeds 50%");
    }

    // ── Snapshot and restore ──────────────────────────────────────────────────────

    @Test
    void snapshot_preserves_derived_cache_and_restore_resets_to_it() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        rt.mutate(Map.of(
            "$.order.subtotal", NF.numberNode(300.0),
            "$.order.tax",      NF.numberNode(30.0)));
        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(330.0);

        var snap = rt.snapshot();

        rt.mutate(Map.of(
            "$.order.subtotal", NF.numberNode(9000.0),
            "$.order.tax",      NF.numberNode(900.0)));
        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(9900.0);

        rt.restore(snap);

        assertThat(rt.getValue("$.order.subtotal").asDouble()).isEqualTo(300.0);
        assertThat(rt.getValue("$.order.tax").asDouble()).isEqualTo(30.0);
        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(330.0);
    }

    @Test
    void restore_after_constraint_violation_already_rolls_back_automatically() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "constraints": [
                { "id": "cap",
                  "expr": "order.amount <= 100",
                  "message": "Over cap",
                  "policy": "rollback" }
              ]
            }
            """);

        rt.mutate("$.order.amount", NF.numberNode(50.0));
        var snap = rt.snapshot();

        // Rollback happens automatically inside mutate()
        assertThatThrownBy(() -> rt.mutate("$.order.amount", NF.numberNode(200.0)))
            .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);

        // After automatic rollback, state still matches the pre-violation snapshot
        assertThat(rt.getValue("$.order.amount").asDouble()).isEqualTo(50.0);

        // An explicit restore to the same snapshot leaves state unchanged
        rt.restore(snap);
        assertThat(rt.getValue("$.order.amount").asDouble()).isEqualTo(50.0);
    }

    // ── Spec evolution ────────────────────────────────────────────────────────────

    @Test
    void spec_evolution_adds_derivation_without_losing_base_state() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        rt.mutate(Map.of(
            "$.order.subtotal", NF.numberNode(200.0),
            "$.order.tax",      NF.numberNode(20.0)));
        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(220.0);

        // Evolve: add amountDue (depends only on base fields, no derivation chain)
        SpecEvolution diff = MAPPER.readValue("""
            { "upsertDerivations": [
                { "path": "$.order.amountDue",
                  "expr": "order.subtotal + order.tax - order.discount" }
            ]}
            """, SpecEvolution.class);

        ModelSpec evolvedSpec   = diff.applyTo(rt.model().spec());
        CompiledModel newModel  = ModelSpecCompiler.compile(evolvedSpec);
        ModelState    newState  = rt.stateView().withModel(newModel);
        ModelRuntime  rt2       = new ModelRuntime(newModel, newState);

        // Base state carries forward
        assertThat(rt2.getValue("$.order.subtotal").asDouble()).isEqualTo(200.0);
        assertThat(rt2.getValue("$.order.tax").asDouble()).isEqualTo(20.0);

        // New derivation activates when a dependency is mutated
        rt2.mutate("$.order.discount", NF.numberNode(15.0));
        assertThat(rt2.getValue("$.order.amountDue").asDouble()).isEqualTo(205.0);
    }

    @Test
    void spec_evolution_removes_derivation_and_exposes_path_as_writable() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.x.computed", "expr": "x.base * 2" }
              ]
            }
            """);

        rt.mutate("$.x.base", NF.numberNode(5.0));
        assertThat(rt.getValue("$.x.computed").asDouble()).isEqualTo(10.0);

        SpecEvolution diff = MAPPER.readValue("""
            { "removeDerivations": ["$.x.computed"] }
            """, SpecEvolution.class);

        ModelSpec    evolved  = diff.applyTo(rt.model().spec());
        CompiledModel model2  = ModelSpecCompiler.compile(evolved);
        ModelState   state2   = rt.stateView().withModel(model2);
        ModelRuntime rt2      = new ModelRuntime(model2, state2);

        // Path is no longer derived; can now be written directly
        rt2.mutate("$.x.computed", NF.numberNode(99.0));
        assertThat(rt2.getValue("$.x.computed").asDouble()).isEqualTo(99.0);
    }

    // ── Explain / traceability ────────────────────────────────────────────────────

    @Test
    void constraint_evaluation_trace_available_via_explain() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "constraints": [
                { "id": "amount-positive",
                  "expr": "order.amount > 0",
                  "message": "Amount must be positive",
                  "policy": "flag" }
              ]
            }
            """);

        rt.mutate("$.order.amount", NF.numberNode(42.0));

        var traces = rt.explain("$constraint:amount-positive");
        assertThat(traces).isNotEmpty();
        assertThat(traces.getFirst().constraintPassed()).isTrue();
        assertThat(traces.getFirst().isConstraint()).isTrue();
    }

    @Test
    void mutation_result_reports_which_derived_fields_were_updated() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "derivations": [
                { "path": "$.order.total",     "expr": "order.subtotal + order.tax" },
                { "path": "$.order.taxRate",   "expr": "order.tax / order.subtotal" }
              ]
            }
            """);

        var result = rt.mutate(Map.of(
            "$.order.subtotal", NF.numberNode(100.0),
            "$.order.tax",      NF.numberNode(10.0)));

        assertThat(result.success()).isTrue();
        assertThat(result.derivedUpdated())
            .containsExactlyInAnyOrder("$.order.total", "$.order.taxRate");
        assertThat(result.mutatedPaths())
            .containsExactlyInAnyOrder("$.order.subtotal", "$.order.tax");
    }

    // ── Car loan amortization schedule ────────────────────────────────────────────

    @Test
    void array_range_syntax_produces_integer_sequence() throws Exception {
        // [low..high] is the JSONata range syntax — $range() is not available in this runtime
        ModelRuntime rt = runtime("""
            {
              "id": "test", "schema": {},
              "derivations": [
                { "path": "$.months", "expr": "[1..n]" }
              ]
            }
            """);

        rt.mutate("$.n", NF.numberNode(4));

        JsonNode months = rt.getValue("$.months");
        assertThat(months.isArray()).isTrue();
        assertThat(months.size()).isEqualTo(4);
        assertThat(months.get(0).asInt()).isEqualTo(1);
        assertThat(months.get(3).asInt()).isEqualTo(4);
    }

    @Test
    void map_builtin_transforms_array_elements_with_lambda() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "test", "schema": {},
              "derivations": [
                { "path": "$.doubled", "expr": "$map([1..n], function($x) {$x * 2})" }
              ]
            }
            """);

        rt.mutate("$.n", NF.numberNode(3));

        JsonNode doubled = rt.getValue("$.doubled");
        assertThat(doubled.isArray()).isTrue();
        assertThat(doubled.size()).isEqualTo(3);
        assertThat(doubled.get(0).asInt()).isEqualTo(2);
        assertThat(doubled.get(1).asInt()).isEqualTo(4);
        assertThat(doubled.get(2).asInt()).isEqualTo(6);
    }

    @Test
    void map_lambda_can_return_object_per_element() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "test", "schema": {},
              "derivations": [
                { "path": "$.rows", "expr": "$map([1..n], function($i) {{\\\"idx\\\": $i, \\\"sq\\\": $i * $i}})" }
              ]
            }
            """);

        rt.mutate("$.n", NF.numberNode(3));

        JsonNode rows = rt.getValue("$.rows");
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isEqualTo(3);
        assertThat(rows.get(0).get("idx").asInt()).isEqualTo(1);
        assertThat(rows.get(0).get("sq").asInt()).isEqualTo(1);
        assertThat(rows.get(1).get("idx").asInt()).isEqualTo(2);
        assertThat(rows.get(1).get("sq").asInt()).isEqualTo(4);
        assertThat(rows.get(2).get("idx").asInt()).isEqualTo(3);
        assertThat(rows.get(2).get("sq").asInt()).isEqualTo(9);
    }

    @Test
    void car_loan_schedule_has_correct_entry_for_each_month() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "car-loan", "schema": {},
              "derivations": [
                { "path": "$.monthlyRate",    "expr": "annualRatePercent / 1200" },
                { "path": "$.compoundFactor", "expr": "$power(1 + monthlyRate, termMonths)" },
                { "path": "$.monthlyPayment", "expr": "$round(loanAmount * monthlyRate * compoundFactor / (compoundFactor - 1), 2)" },
                { "path": "$.totalPayment",   "expr": "$round(monthlyPayment * termMonths, 2)" },
                { "path": "$.totalInterest",  "expr": "$round(totalPayment - loanAmount, 2)" },
                { "path": "$.schedule",       "expr": "$map([1..termMonths], function($n) {{\\\"month\\\": $n, \\\"payment\\\": monthlyPayment, \\\"interest\\\": $round(loanAmount * monthlyRate * (compoundFactor - $power(1 + monthlyRate, $n - 1)) / (compoundFactor - 1), 2), \\\"principal\\\": $round(loanAmount * monthlyRate * $power(1 + monthlyRate, $n - 1) / (compoundFactor - 1), 2), \\\"balance\\\": $round(loanAmount * (compoundFactor - $power(1 + monthlyRate, $n)) / (compoundFactor - 1), 2)}})" }
              ]
            }
            """);

        rt.mutate(Map.of(
            "$.loanAmount",        NF.numberNode(20000),
            "$.annualRatePercent", NF.numberNode(6),
            "$.termMonths",        NF.numberNode(60)));

        // Scalar derivations
        assertThat(rt.getValue("$.monthlyPayment").asDouble()).isEqualTo(386.66);
        assertThat(rt.getValue("$.totalPayment").asDouble()).isEqualTo(23199.60);
        assertThat(rt.getValue("$.totalInterest").asDouble()).isEqualTo(3199.60);

        // Schedule array
        JsonNode schedule = rt.getValue("$.schedule");
        assertThat(schedule.isArray()).as("$.schedule must be an array").isTrue();
        assertThat(schedule.size()).isEqualTo(60);

        // Month 1: interest = loanAmount * monthlyRate = 20 000 * 0.005 = 100.00 exactly
        JsonNode m1 = schedule.get(0);
        assertThat(m1.get("month").asInt()).isEqualTo(1);
        assertThat(m1.get("interest").asDouble()).isEqualTo(100.00);
        assertThat(m1.get("principal").asDouble()).isCloseTo(286.66, within(0.01));
        assertThat(m1.get("balance").asDouble()).isCloseTo(19713.34, within(0.01));

        // Last month: remaining balance rounds to ~0
        JsonNode m60 = schedule.get(59);
        assertThat(m60.get("month").asInt()).isEqualTo(60);
        assertThat(m60.get("balance").asDouble()).isCloseTo(0.0, within(1.0));
    }

    // ── Helper ────────────────────────────────────────────────────────────────────

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec     spec  = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState    state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
