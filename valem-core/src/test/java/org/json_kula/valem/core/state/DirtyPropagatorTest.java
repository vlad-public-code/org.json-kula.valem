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

    // ── ancestor (descendant→ancestor) propagation ─────────────────────────────

    @Test
    void element_index_mutation_dirties_bare_array_dependent() {
        // A $count(arr[$ != ""])-style derivation extracts a dependency on the BARE array "$.arr".
        // Mutating an element "$.arr[0]" must still dirty it (the bug this fixes: it did not).
        DependencyGraph g = DependencyGraph.builder()
                .addNode("$.arr", DependencyGraph.NodeKind.BASE)
                .addEdge("$.arr", "$.count")
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.arr[0]"));
        assertThat(dirty).contains("$.count");
    }

    @Test
    void nested_field_mutation_dirties_ancestor_dependents() {
        // Mutating "$.a.b.c" changes containers "$.a.b" and "$.a"; dependents on either recompute.
        DependencyGraph g = DependencyGraph.builder()
                .addNode("$.a",   DependencyGraph.NodeKind.BASE)
                .addNode("$.a.b", DependencyGraph.NodeKind.BASE)
                .addEdge("$.a",   "$.dependsOnA")
                .addEdge("$.a.b", "$.dependsOnAB")
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.a.b.c"));
        assertThat(dirty).contains("$.dependsOnA", "$.dependsOnAB");
    }

    @Test
    void ancestorContainers_enumerates_containers_nearest_first() {
        assertThat(DirtyPropagator.ancestorContainers("$.arr[0]")).containsExactly("$.arr");
        assertThat(DirtyPropagator.ancestorContainers("$.a.b.c")).containsExactly("$.a.b", "$.a");
        assertThat(DirtyPropagator.ancestorContainers("$.items[2].qty"))
                .containsExactly("$.items[2]", "$.items");
        assertThat(DirtyPropagator.ancestorContainers("$.total")).isEmpty();
    }

    @Test
    void ancestor_rule_does_not_dirty_unrelated_siblings() {
        // Mutating "$.a.b" must not touch a dependent of a sibling container "$.x".
        DependencyGraph g = DependencyGraph.builder()
                .addNode("$.x", DependencyGraph.NodeKind.BASE)
                .addEdge("$.x", "$.dependsOnX")
                .build();

        Set<String> dirty = DirtyPropagator.propagate(g, Set.of("$.a.b"));
        assertThat(dirty).doesNotContain("$.dependsOnX");
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
