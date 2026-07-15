package org.json_kula.valem.core.state;

import org.json_kula.valem.core.graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DirtyPropagatorTest {

    // ── matchesPattern ─────────────────────────────────────────────────────────

    @Test
    void wildcard_matches_any_index() {
        assertThat(DirtyPropagator.matchesPattern("$.order.items[*].qty", "$.order.items[0].qty")).isTrue();
        assertThat(DirtyPropagator.matchesPattern("$.order.items[*].qty", "$.order.items[99].qty")).isTrue();
    }

    @Test
    void wildcard_matches_any_field_name() {
        assertThat(DirtyPropagator.matchesPattern("$.row[*].value", "$.row.name.value")).isTrue();
    }

    @Test
    void different_segment_count_does_not_match() {
        assertThat(DirtyPropagator.matchesPattern("$.order.items[*].qty", "$.order.items.qty")).isFalse();
    }

    @Test
    void exact_match_required_for_non_wildcard() {
        assertThat(DirtyPropagator.matchesPattern("$.order.items[*].qty", "$.order.items[0].price")).isFalse();
    }

    @Test
    void no_wildcard_requires_exact_path() {
        assertThat(DirtyPropagator.matchesPattern("$.order.total", "$.order.total")).isTrue();
        assertThat(DirtyPropagator.matchesPattern("$.order.total", "$.order.subtotal")).isFalse();
    }

    // ── propagate ─────────────────────────────────────────────────────────────

    @Test
    void direct_dependency_propagated() {
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("$.order.subtotal", "$.order.total")
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.order.subtotal"));
        assertThat(dirty).contains("$.order.total");
    }

    @Test
    void transitive_chain_fully_propagated() {
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("$.order.subtotal", "$.order.total")
                .addEdge("$.order.total",    "$.order.amountDue")
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.order.subtotal"));
        assertThat(dirty).containsExactlyInAnyOrder("$.order.total", "$.order.amountDue");
    }

    @Test
    void mutation_with_no_dependents_yields_empty_set() {
        DependencyGraph g = DependencyGraph.builder()
                .addNode("$.order.total", DependencyGraph.NodeKind.BASE)
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.order.total"));
        assertThat(dirty).isEmpty();
    }

    @Test
    void multiple_mutated_paths_union_of_dependents() {
        DependencyGraph g = DependencyGraph.builder()
                .addEdge("$.order.subtotal", "$.order.total")
                .addEdge("$.order.tax",      "$.order.total")
                .addEdge("$.order.discount", "$.order.amountDue")
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.order.subtotal", "$.order.discount"));
        assertThat(dirty).containsExactlyInAnyOrder("$.order.total", "$.order.amountDue");
    }

    @Test
    void wildcard_pattern_node_picked_up_on_concrete_mutation() {
        // Graph has edge: "$.order.items[*].qty" → "$.order.total"
        // Mutating "$.order.items[0].qty" should dirty "$.order.total"
        DependencyGraph g = DependencyGraph.builder()
                .addNode("$.order.items[*].qty", DependencyGraph.NodeKind.BASE)
                .addEdge("$.order.items[*].qty", "$.order.total")
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.order.items[0].qty"));
        assertThat(dirty).contains("$.order.total");
    }

    // ── isPrefixOf ────────────────────────────────────────────────────────────

    @Test
    void isPrefixOf_detects_proper_prefix() {
        assertThat(DirtyPropagator.isPrefixOf("$.items",    "$.items[*].price")).isTrue();
        assertThat(DirtyPropagator.isPrefixOf("$.items[0]", "$.items[*].price")).isTrue();
        assertThat(DirtyPropagator.isPrefixOf("$.items",    "$.items[*].qty")).isTrue();
    }

    @Test
    void isPrefixOf_rejects_equal_path() {
        assertThat(DirtyPropagator.isPrefixOf("$.items", "$.items")).isFalse();
    }

    @Test
    void isPrefixOf_rejects_different_root() {
        assertThat(DirtyPropagator.isPrefixOf("$.items", "$.total")).isFalse();
    }

    @Test
    void parent_path_mutation_dirtied_wildcard_dependents() {
        // Mutating the whole array "$.items" must dirty "$.total" which depends on "$.items[*].price"
        DependencyGraph g = DependencyGraph.builder()
                .addNode("$.items[*].price", DependencyGraph.NodeKind.BASE)
                .addEdge("$.items[*].price", "$.total")
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.items"));
        assertThat(dirty).contains("$.total");
    }

    @Test
    void parent_path_mutation_dirtied_transitively() {
        // $.items → dirtied $.items[*].price → dirtied $.lineTotal → dirtied $.total
        DependencyGraph g = DependencyGraph.builder()
                .addNode("$.items[*].price", DependencyGraph.NodeKind.BASE)
                .addEdge("$.items[*].price", "$.items[*].lineTotal")
                .addEdge("$.items[*].lineTotal", "$.total")
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.items"));
        assertThat(dirty).containsAnyOf("$.total", "$.items[*].lineTotal");
    }
}
