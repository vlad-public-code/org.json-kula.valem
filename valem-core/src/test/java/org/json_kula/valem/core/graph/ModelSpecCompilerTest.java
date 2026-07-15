package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.json_kula.valem.core.model.ModelSpec;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelSpecCompilerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Minimal spec ──────────────────────────────────────────────────────────────

    @Test
    void empty_spec_compiles_to_empty_graph() throws Exception {
        ModelSpec spec = parse("""
            { "id": "m", "schema": {} }
            """);
        CompiledModel cm = ModelSpecCompiler.compile(spec);
        assertThat(cm.graph().nodes()).isEmpty();
        assertThat(cm.derivedPaths()).isEmpty();
        assertThat(cm.metaNodeKeys()).isEmpty();
    }

    // ── Derivation wiring ──────────────────────────────────────────────────────

    @Test
    void derivation_registers_derived_node_and_base_deps() throws Exception {
        ModelSpec spec = parse("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        CompiledModel cm = ModelSpecCompiler.compile(spec);
        DependencyGraph g = cm.graph();

        assertThat(g.nodeInfo("$.order.total").kind()).isEqualTo(DependencyGraph.NodeKind.DERIVED);
        assertThat(g.nodeInfo("$.order.subtotal").kind()).isEqualTo(DependencyGraph.NodeKind.BASE);
        assertThat(g.nodeInfo("$.order.tax").kind()).isEqualTo(DependencyGraph.NodeKind.BASE);

        assertThat(g.dependentsOf("$.order.subtotal")).contains("$.order.total");
        assertThat(g.dependentsOf("$.order.tax")).contains("$.order.total");
        assertThat(g.dependenciesOf("$.order.total"))
                .containsExactlyInAnyOrder("$.order.subtotal", "$.order.tax");
    }

    @Test
    void derivation_lookup_works() throws Exception {
        ModelSpec spec = parse("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        CompiledModel cm = ModelSpecCompiler.compile(spec);
        assertThat(cm.derivationFor("$.order.total")).isNotNull();
        assertThat(cm.derivationFor("$.order.total").expr()).isEqualTo("order.subtotal + order.tax");
        assertThat(cm.derivationFor("$.order.subtotal")).isNull();
    }

    @Test
    void chained_derivations_produce_correct_evaluation_order() throws Exception {
        // total = subtotal + tax; amountDue = total - discount
        ModelSpec spec = parse("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total",     "expr": "order.subtotal + order.tax" },
                { "path": "$.order.amountDue", "expr": "order.total - order.discount" }
              ]
            }
            """);

        CompiledModel cm = ModelSpecCompiler.compile(spec);
        List<String> order = cm.graph().evaluationOrder();
        assertThat(order.indexOf("$.order.total"))
                .isLessThan(order.indexOf("$.order.amountDue"));
    }

    // ── Meta-derivation wiring ─────────────────────────────────────────────────

    @Test
    void meta_derivation_registers_meta_node_with_correct_key() throws Exception {
        ModelSpec spec = parse("""
            {
              "id": "m", "schema": {},
              "metaDerivations": [
                { "path": "$.order.downPayment", "property": "minimum", "expr": "order.total * 0.2" }
              ]
            }
            """);

        CompiledModel cm = ModelSpecCompiler.compile(spec);
        assertThat(cm.metaNodeKeys()).containsExactly("$.order.downPayment#minimum");
        assertThat(cm.graph().nodeInfo("$.order.downPayment#minimum").kind())
                .isEqualTo(DependencyGraph.NodeKind.META);
        assertThat(cm.metaDerivationFor("$.order.downPayment#minimum")).isNotNull();

        // order.total must be upstream of the meta node
        assertThat(cm.graph().dependentsOf("$.order.total"))
                .contains("$.order.downPayment#minimum");
    }

    // ── Constraint wiring ──────────────────────────────────────────────────────

    @Test
    void constraint_synthetic_node_registered_and_downstream_of_deps() throws Exception {
        ModelSpec spec = parse("""
            {
              "id": "m", "schema": {},
              "constraints": [
                { "id": "credit-check", "expr": "order.total <= customer.creditLimit",
                  "message": "Over limit", "policy": "rollback" }
              ]
            }
            """);

        CompiledModel cm = ModelSpecCompiler.compile(spec);
        String key = "$constraint:credit-check";
        assertThat(cm.graph().nodes()).contains(key);
        assertThat(cm.graph().dependentsOf("$.order.total")).contains(key);
        assertThat(cm.graph().dependentsOf("$.customer.creditLimit")).contains(key);
    }

    // ── Dirty propagation through derived chains ───────────────────────────────

    @Test
    void changing_base_field_propagates_to_all_downstream() throws Exception {
        ModelSpec spec = parse("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total",     "expr": "order.subtotal + order.tax" },
                { "path": "$.order.amountDue", "expr": "order.total - order.discount" }
              ],
              "metaDerivations": [
                { "path": "$.order.downPayment", "property": "minimum", "expr": "order.total * 0.2" }
              ],
              "constraints": [
                { "id": "credit-check", "expr": "order.total <= customer.creditLimit",
                  "message": "Over limit", "policy": "rollback" }
              ]
            }
            """);

        CompiledModel cm = ModelSpecCompiler.compile(spec);
        java.util.Set<String> dirty = cm.graph().transitivelyDependentOn("$.order.subtotal");

        assertThat(dirty).contains(
                "$.order.total",
                "$.order.amountDue",
                "$.order.downPayment#minimum",
                "$constraint:credit-check");
    }

    // ── Cycle detection ────────────────────────────────────────────────────────

    @Test
    void circular_derivation_throws_cyclic_exception() throws Exception {
        ModelSpec spec = parse("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.a", "expr": "b" },
                { "path": "$.b", "expr": "a" }
              ]
            }
            """);

        assertThatThrownBy(() -> ModelSpecCompiler.compile(spec))
                .isInstanceOf(DependencyGraph.CyclicDependencyException.class);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ModelSpec parse(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }
}
