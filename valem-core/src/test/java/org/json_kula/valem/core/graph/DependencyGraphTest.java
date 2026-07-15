package org.json_kula.valem.core.graph;

import org.junit.jupiter.api.Test;
import org.json_kula.valem.core.graph.DependencyGraph.NodeKind;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyGraphTest {

    // â”€â”€ Basic structure â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void empty_graph_builds() {
        DependencyGraph g = DependencyGraph.builder().build();
        assertThat(g.nodes()).isEmpty();
        assertThat(g.evaluationOrder()).isEmpty();
    }

    @Test
    void single_node_has_no_edges() {
        DependencyGraph g = DependencyGraph.builder()
                .addNode("order.total", NodeKind.BASE)
                .build();

        assertThat(g.nodes()).containsExactly("order.total");
        assertThat(g.dependentsOf("order.total")).isEmpty();
        assertThat(g.dependenciesOf("order.total")).isEmpty();
        assertThat(g.evaluationOrder()).containsExactly("order.total");
    }

    @Test
    void node_info_reflects_registered_kind() {
        DependencyGraph g = DependencyGraph.builder()
                .addNode("order.subtotal", NodeKind.BASE)
                .addNode("order.total",    NodeKind.DERIVED)
                .build();

        assertThat(g.nodeInfo("order.subtotal").kind()).isEqualTo(NodeKind.BASE);
        assertThat(g.nodeInfo("order.total").kind()).isEqualTo(NodeKind.DERIVED);
        assertThat(g.nodeInfo("nonexistent")).isNull();
    }

    // â”€â”€ Simple chain â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void linear_chain_topological_order() {
        // subtotal â†’ total â†’ tax (total depends on subtotal; tax depends on total)
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("order.subtotal", "order.total")
                .addEdge("order.total",    "order.tax")
                .build();

        List<String> order = g.evaluationOrder();
        assertThat(order.indexOf("order.subtotal"))
                .isLessThan(order.indexOf("order.total"));
        assertThat(order.indexOf("order.total"))
                .isLessThan(order.indexOf("order.tax"));
    }

    @Test
    void direct_edge_is_bidirectionally_accessible() {
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("order.subtotal", "order.total")
                .build();

        assertThat(g.dependentsOf("order.subtotal")).containsExactly("order.total");
        assertThat(g.dependenciesOf("order.total")).containsExactly("order.subtotal");
    }

    // â”€â”€ Multiple dependencies â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void node_with_two_dependencies_appears_after_both() {
        // total = subtotal + tax
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("order.subtotal", "order.total")
                .addEdge("order.tax",      "order.total")
                .build();

        List<String> order = g.evaluationOrder();
        int totalIdx = order.indexOf("order.total");
        assertThat(order.indexOf("order.subtotal")).isLessThan(totalIdx);
        assertThat(order.indexOf("order.tax")).isLessThan(totalIdx);
    }

    // â”€â”€ Diamond â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void diamond_dependency_preserved() {
        // a â†’ b, a â†’ c, b â†’ d, c â†’ d
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("a", "b")
                .addEdge("a", "c")
                .addEdge("b", "d")
                .addEdge("c", "d")
                .build();

        List<String> order = g.evaluationOrder();
        int a = order.indexOf("a");
        int b = order.indexOf("b");
        int c = order.indexOf("c");
        int d = order.indexOf("d");
        assertThat(a).isLessThan(b);
        assertThat(a).isLessThan(c);
        assertThat(b).isLessThan(d);
        assertThat(c).isLessThan(d);
    }

    // â”€â”€ Transitive dependents â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void transitive_dependents_traversed_by_bfs() {
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("order.subtotal", "order.total")
                .addEdge("order.total",    "order.amountDue")
                .addEdge("order.total",    "order.tax")
                .build();

        Set<String> downstream = g.transitivelyDependentOn("order.subtotal");
        assertThat(downstream).containsExactlyInAnyOrder("order.total", "order.amountDue", "order.tax");
    }

    @Test
    void transitive_dependents_of_leaf_is_empty() {
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("a", "b")
                .build();

        assertThat(g.transitivelyDependentOn("b")).isEmpty();
    }

    // â”€â”€ Cycle detection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void direct_cycle_throws() {
        assertThatThrownBy(() ->
                DependencyGraph.builder()
                        .addEdge("a", "b")
                        .addEdge("b", "a")
                        .build())
                .isInstanceOf(DependencyGraph.CyclicDependencyException.class)
                .satisfies(ex -> {
                    var cde = (DependencyGraph.CyclicDependencyException) ex;
                    assertThat(cde.involvedNodes()).containsExactlyInAnyOrder("a", "b");
                });
    }

    @Test
    void indirect_cycle_throws() {
        assertThatThrownBy(() ->
                DependencyGraph.builder()
                        .addEdge("x", "y")
                        .addEdge("y", "z")
                        .addEdge("z", "x")
                        .build())
                .isInstanceOf(DependencyGraph.CyclicDependencyException.class)
                .satisfies(ex -> {
                    var cde = (DependencyGraph.CyclicDependencyException) ex;
                    assertThat(cde.involvedNodes()).containsExactlyInAnyOrder("x", "y", "z");
                });
    }

    @Test
    void cycle_error_message_names_involved_nodes() {
        assertThatThrownBy(() ->
                DependencyGraph.builder().addEdge("p", "p").build())
                .isInstanceOf(DependencyGraph.CyclicDependencyException.class)
                .hasMessageContaining("p");
    }

    // â”€â”€ Idempotence â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // ── Evaluation levels ─────────────────────────────────────────────────────────

    @Test
    void empty_graph_has_no_levels() {
        DependencyGraph g = DependencyGraph.builder().build();
        assertThat(g.evaluationLevels()).isEmpty();
    }

    @Test
    void isolated_node_is_at_level_0() {
        DependencyGraph g = DependencyGraph.builder()
                .addNode("a", NodeKind.BASE)
                .build();
        assertThat(g.evaluationLevels()).hasSize(1);
        assertThat(g.evaluationLevels().getFirst()).containsExactly("a");
    }

    @Test
    void linear_chain_each_node_in_its_own_level() {
        // a -> b -> c: depths 0, 1, 2
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("a", "b")
                .addEdge("b", "c")
                .build();
        List<List<String>> levels = g.evaluationLevels();
        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).containsExactly("a");
        assertThat(levels.get(1)).containsExactly("b");
        assertThat(levels.get(2)).containsExactly("c");
    }

    @Test
    void independent_nodes_share_level_0() {
        DependencyGraph g = DependencyGraph.builder()
                .addNode("x", NodeKind.DERIVED)
                .addNode("y", NodeKind.DERIVED)
                .addNode("z", NodeKind.DERIVED)
                .build();
        assertThat(g.evaluationLevels()).hasSize(1);
        assertThat(g.evaluationLevels().getFirst()).containsExactlyInAnyOrder("x", "y", "z");
    }

    @Test
    void diamond_assigns_correct_levels() {
        // a -> b, a -> c, b -> d, c -> d  =>  a=0, b=c=1, d=2
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("a", "b")
                .addEdge("a", "c")
                .addEdge("b", "d")
                .addEdge("c", "d")
                .build();
        List<List<String>> levels = g.evaluationLevels();
        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).containsExactlyInAnyOrder("a");
        assertThat(levels.get(1)).containsExactlyInAnyOrder("b", "c");
        assertThat(levels.get(2)).containsExactlyInAnyOrder("d");
    }

    @Test
    void all_nodes_in_levels_form_same_set_as_evaluation_order() {
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("a", "b")
                .addEdge("a", "c")
                .addEdge("b", "d")
                .build();
        List<String> flattened = g.evaluationLevels().stream()
                .flatMap(List::stream)
                .toList();
        assertThat(flattened).containsExactlyInAnyOrderElementsOf(g.evaluationOrder());
    }

    // ── Idempotence ───────────────────────────────────────────────────────────────

    @Test
    void adding_same_node_twice_is_idempotent() {
        DependencyGraph g = DependencyGraph.builder()
                .addNode("a", NodeKind.BASE)
                .addNode("a", NodeKind.DERIVED) // second add ignored
                .build();

        assertThat(g.nodes()).hasSize(1);
        assertThat(g.nodeInfo("a").kind()).isEqualTo(NodeKind.BASE); // first kind wins
    }

    // â”€â”€ Spec-realistic scenario â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void order_model_dependency_structure() {
        // order.total = order.subtotal + order.tax
        // order.amountDue = order.total - order.deposit
        // meta: order.deposit#minimum = order.total * 0.2
        DependencyGraph g = DependencyGraph.builder()
                .addNode("order.subtotal",          NodeKind.BASE)
                .addNode("order.tax",               NodeKind.BASE)
                .addNode("order.deposit",            NodeKind.BASE)
                .addNode("order.total",              NodeKind.DERIVED)
                .addNode("order.amountDue",          NodeKind.DERIVED)
                .addNode("order.deposit#minimum",    NodeKind.META)
                .addEdge("order.subtotal",           "order.total")
                .addEdge("order.tax",                "order.total")
                .addEdge("order.total",              "order.amountDue")
                .addEdge("order.deposit",            "order.amountDue")
                .addEdge("order.total",              "order.deposit#minimum")
                .build();

        List<String> order = g.evaluationOrder();

        // subtotal and tax must precede total
        assertThat(order.indexOf("order.subtotal")).isLessThan(order.indexOf("order.total"));
        assertThat(order.indexOf("order.tax")).isLessThan(order.indexOf("order.total"));
        // total must precede amountDue and the meta node
        assertThat(order.indexOf("order.total")).isLessThan(order.indexOf("order.amountDue"));
        assertThat(order.indexOf("order.total")).isLessThan(order.indexOf("order.deposit#minimum"));

        // Changing order.subtotal â†’ total, amountDue, and meta node all dirty
        Set<String> dirty = g.transitivelyDependentOn("order.subtotal");
        assertThat(dirty).containsExactlyInAnyOrder("order.total", "order.amountDue", "order.deposit#minimum");
    }
}

