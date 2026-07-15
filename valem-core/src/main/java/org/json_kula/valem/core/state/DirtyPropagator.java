package org.json_kula.valem.core.state;

import org.json_kula.valem.core.graph.DependencyGraph;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Computes the full set of nodes that must be re-evaluated after a mutation.
 *
 * <p>Given a set of directly-mutated base-field paths, this class walks the
 * {@link DependencyGraph} transitively to collect every derived field, meta node,
 * and constraint node that depends — directly or indirectly — on any of the
 * mutated paths.
 *
 * <p>For array-wildcard dependencies (e.g. a derivation that reads
 * {@code $.order.items[*].qty}), the dependency graph contains an edge from the
 * pattern path {@code "$.order.items[*].qty"} to the derived node. Mutation of a
 * concrete path such as {@code "$.order.items[0].qty"} needs to be matched against
 * these patterns by the caller via {@link #matchesPattern} before invoking
 * {@link #propagate}.
 */
public final class DirtyPropagator {

    private DirtyPropagator() {}

    /**
     * Returns every graph node (derived fields, meta nodes, constraint nodes) that
     * transitively depends on any path in {@code mutatedPaths}.
     *
     * <p>The returned set is in BFS discovery order — it does not guarantee evaluation
     * order; use {@link DependencyGraph#evaluationOrder()} for that.
     *
     * @param graph        the compiled dependency graph
     * @param mutatedPaths concrete JsonPath expressions that were directly written
     * @return all downstream nodes that must be re-evaluated
     */
    public static Set<String> propagate(DependencyGraph graph, Set<String> mutatedPaths) {
        Set<String> dirty = new LinkedHashSet<>();
        // Memoise transitive-dependent lookups within this call: mutating "$.items[0].qty" and
        // "$.items[1].qty" both resolve to the same "$.items[*].qty" subtree (audit CPU-2).
        Map<String, Set<String>> transitiveMemo = new HashMap<>();
        for (String path : mutatedPaths) {
            // Direct match in the graph
            dirty.addAll(transitive(graph, path, transitiveMemo));

            // Wildcard-pattern match: only the precomputed wildcard nodes can match, so we no longer
            // scan every concrete node. e.g. mutated "$.items[0].price" matches "$.items[*].price".
            for (String node : graph.wildcardNodes()) {
                if (matchesPattern(node, path)) {
                    dirty.addAll(transitive(graph, node, transitiveMemo));
                    dirty.add(node);
                }
            }

            // Prefix match: e.g. mutating "$.items" (whole array) should dirty "$.items[*].price"
            // and every node that transitively depends on it. This must consider all nodes.
            for (String node : graph.nodes()) {
                if (isPrefixOf(path, node)) {
                    dirty.addAll(transitive(graph, node, transitiveMemo));
                    dirty.add(node);
                }
            }
        }
        return dirty;
    }

    private static Set<String> transitive(DependencyGraph graph, String key,
                                          Map<String, Set<String>> memo) {
        return memo.computeIfAbsent(key, graph::transitivelyDependentOn);
    }

    /**
     * Returns {@code true} if the concrete path {@code concretePath} matches the
     * wildcard pattern {@code patternPath}.
     *
     * <p>A pattern segment {@code [*]} matches any single concrete segment (either
     * a field name or an array index). Non-wildcard segments must match exactly.
     *
     * <p>Examples:
     * <pre>
     *   matchesPattern("$.order.items[*].qty", "$.order.items[0].qty") → true
     *   matchesPattern("$.order.items[*].qty", "$.order.items[1].qty") → true
     *   matchesPattern("$.order.items[*].qty", "$.order.qty")          → false (segment count differs)
     *   matchesPattern("$.order.items[*]",     "$.order.items[0]")     → true
     * </pre>
     */
    /**
     * Returns {@code true} if {@code prefixPath} is a proper path-prefix of {@code nodePath}.
     *
     * <p>Used to propagate dirty state when a parent path (e.g. {@code $.items}) is mutated
     * and descendant wildcard-pattern nodes (e.g. {@code $.items[*].price}) must be dirtied.
     *
     * <p>Segment matching treats {@code [*]} in either path as a wildcard that matches any
     * single segment (so {@code $.items[0]} is a prefix of {@code $.items[*].price}).
     */
    static boolean isPrefixOf(String prefixPath, String nodePath) {
        java.util.List<String> prefix = PathConverter.toSegments(prefixPath);
        java.util.List<String> node   = PathConverter.toSegments(nodePath);
        if (prefix.size() >= node.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            String p = prefix.get(i);
            String n = node.get(i);
            if ("[*]".equals(p) || "[*]".equals(n)) continue;
            if (!p.equals(n)) return false;
        }
        return true;
    }

    public static boolean matchesPattern(String patternPath, String concretePath) {
        java.util.List<String> pattern  = PathConverter.toSegments(patternPath);
        java.util.List<String> concrete = PathConverter.toSegments(concretePath);

        if (pattern.size() != concrete.size()) return false;
        for (int i = 0; i < pattern.size(); i++) {
            String p = pattern.get(i);
            if ("[*]".equals(p)) continue; // wildcard matches anything
            if (!p.equals(concrete.get(i))) return false;
        }
        return true;
    }
}
