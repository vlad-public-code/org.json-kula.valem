package org.json_kula.valem.core.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Directed acyclic graph of computation dependencies for a compiled model.
 *
 * <p>Nodes are string keys in one of three categories:
 * <ul>
 *   <li><b>Base</b> â€” writable state fields (JSONPath dot-notation, e.g. {@code "order.total"})</li>
 *   <li><b>Derived</b> â€” computed by a derivation expression (e.g. {@code "order.total"})</li>
 *   <li><b>Meta</b> â€” per-field metadata computed by a metaDerivation (e.g. {@code "order.downPayment#minimum"})</li>
 * </ul>
 *
 * <p>An edge {@code A â†’ B} means "node B depends on node A" (i.e. B must be re-evaluated
 * when A changes). The evaluation order is the reverse topological sort of this edge direction:
 * nodes with no incoming edges are evaluated first.
 *
 * <p>Instances are immutable once built. Use {@link Builder} to construct.
 */
public final class DependencyGraph {

    /** Categories of nodes in the graph. */
    public enum NodeKind { BASE, DERIVED, META }

    /** Metadata stored per node. */
    public record NodeInfo(String key, NodeKind kind) {}

    // key â†’ NodeInfo
    private final Map<String, NodeInfo> nodes;

    // key â†’ set of keys that directly depend on it (downstream / successors)
    private final Map<String, Set<String>> dependents;

    // key â†’ set of keys that it directly depends on (upstream / predecessors)
    private final Map<String, Set<String>> dependencies;

    // Topological evaluation order (leaves first)
    private final List<String> evaluationOrder;

    // Nodes grouped by dependency depth; nodes at the same depth are mutually independent
    private final List<List<String>> evaluationLevels;

    // Precomputed subset of node keys containing a wildcard segment "[*]" (audit CPU-2): dirty
    // propagation only needs to pattern-match a mutated concrete path against these, not all nodes.
    private final List<String> wildcardNodes;

    private DependencyGraph(
            Map<String, NodeInfo> nodes,
            Map<String, Set<String>> dependents,
            Map<String, Set<String>> dependencies,
            List<String> evaluationOrder,
            List<List<String>> evaluationLevels) {
        this.nodes           = Collections.unmodifiableMap(nodes);
        this.dependents      = Collections.unmodifiableMap(dependents);
        this.dependencies    = Collections.unmodifiableMap(dependencies);
        this.evaluationOrder = Collections.unmodifiableList(evaluationOrder);
        this.evaluationLevels = evaluationLevels;

        List<String> wildcards = new ArrayList<>();
        for (String key : nodes.keySet()) {
            if (key.contains("[*]")) wildcards.add(key);
        }
        this.wildcardNodes = Collections.unmodifiableList(wildcards);
    }

    /** Node keys that contain a wildcard segment {@code [*]} (precomputed for dirty propagation). */
    public List<String> wildcardNodes() { return wildcardNodes; }

    // â”€â”€ Queries â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** All node keys in the graph. */
    public Set<String> nodes() { return nodes.keySet(); }

    /** Metadata for a node, or {@code null} if the key is unknown. */
    public NodeInfo nodeInfo(String key) { return nodes.get(key); }

    /** Direct downstream dependents of {@code key} (nodes that must re-evaluate when key changes). */
    public Set<String> dependentsOf(String key) {
        return dependents.getOrDefault(key, Set.of());
    }

    /** Direct upstream dependencies of {@code key} (nodes that key reads from). */
    public Set<String> dependenciesOf(String key) {
        return dependencies.getOrDefault(key, Set.of());
    }

    /**
     * All nodes that transitively depend on {@code key} (BFS from key following dependent edges).
     * Useful for dirty propagation: when {@code key} changes, all returned nodes must be re-evaluated.
     */
    public Set<String> transitivelyDependentOn(String key) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(key);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String dep : dependentsOf(current)) {
                if (visited.add(dep)) queue.add(dep);
            }
        }
        return Collections.unmodifiableSet(visited);
    }

    /**
     * The topological evaluation order: nodes with no upstream dependencies come first,
     * derived/meta nodes that depend on them come later. Evaluate in this order after
     * a mutation to ensure every node sees up-to-date inputs.
     */
    public List<String> evaluationOrder() { return evaluationOrder; }

    /**
     * Nodes grouped by dependency depth level. All nodes within the same level are
     * mutually independent and may be evaluated in parallel. Levels are ordered
     * from shallowest (no dependencies) to deepest.
     */
    public List<List<String>> evaluationLevels() { return evaluationLevels; }

    // â”€â”€ Builder â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private final Map<String, NodeInfo>  nodes       = new LinkedHashMap<>();
        private final Map<String, Set<String>> dependents  = new HashMap<>();
        private final Map<String, Set<String>> dependencies = new HashMap<>();

        private Builder() {}

        /** Registers a node (idempotent â€” re-adding with the same key is a no-op). */
        public Builder addNode(String key, NodeKind kind) {
            nodes.putIfAbsent(key, new NodeInfo(key, kind));
            dependents.putIfAbsent(key, new LinkedHashSet<>());
            dependencies.putIfAbsent(key, new LinkedHashSet<>());
            return this;
        }

        /**
         * Adds an edge: {@code dependent} reads from {@code dependency}.
         * Both nodes are registered automatically if not yet present.
         * The kind for auto-registered nodes defaults to {@link NodeKind#BASE}.
         */
        public Builder addEdge(String dependency, String dependent) {
            addNode(dependency, NodeKind.BASE);
            addNode(dependent,  NodeKind.BASE);
            dependents .get(dependency).add(dependent);
            dependencies.get(dependent).add(dependency);
            return this;
        }

        /**
         * Builds the graph, validating that it is acyclic.
         *
         * @throws CyclicDependencyException if the graph contains a cycle
         */
        public DependencyGraph build() {
            List<String> order = topologicalSort(nodes.keySet(), dependents);

            // Make adjacency sets immutable
            Map<String, Set<String>> immutableDependents  = new LinkedHashMap<>();
            Map<String, Set<String>> immutableDependencies = new LinkedHashMap<>();
            for (String key : nodes.keySet()) {
                immutableDependents .put(key, Collections.unmodifiableSet(new LinkedHashSet<>(dependents .get(key))));
                immutableDependencies.put(key, Collections.unmodifiableSet(new LinkedHashSet<>(dependencies.get(key))));
            }

            List<List<String>> levels = computeEvaluationLevels(order, immutableDependencies);

            return new DependencyGraph(
                    new LinkedHashMap<>(nodes),
                    immutableDependents,
                    immutableDependencies,
                    order,
                    levels);
        }
    }

    // â”€â”€ Topological sort (Kahn's algorithm) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Groups nodes by dependency depth. A node's depth is 1 + max(depth of all its
     * direct predecessors); leaf nodes (no dependencies) are at depth 0.
     * Topological order guarantees predecessors are depth-assigned before each node.
     */
    private static List<List<String>> computeEvaluationLevels(
            List<String> topologicalOrder,
            Map<String, Set<String>> dependencies) {

        if (topologicalOrder.isEmpty()) return List.of();

        Map<String, Integer> depth = new HashMap<>();
        int maxDepth = 0;
        for (String node : topologicalOrder) {
            int d = 0;
            for (String dep : dependencies.getOrDefault(node, Set.of())) {
                d = Math.max(d, depth.getOrDefault(dep, 0) + 1);
            }
            depth.put(node, d);
            if (d > maxDepth) maxDepth = d;
        }

        List<List<String>> levels = new ArrayList<>(maxDepth + 1);
        for (int i = 0; i <= maxDepth; i++) levels.add(new ArrayList<>());
        for (String node : topologicalOrder) levels.get(depth.get(node)).add(node);

        List<List<String>> result = new ArrayList<>(maxDepth + 1);
        for (List<String> level : levels) result.add(Collections.unmodifiableList(level));
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns nodes in topological order (sources first) using Kahn's algorithm.
     * Throws {@link CyclicDependencyException} if a cycle is detected.
     */
    private static List<String> topologicalSort(
            Collection<String> allNodes,
            Map<String, Set<String>> dependents) {

        // in-degree = number of predecessors (dependencies)
        Map<String, Integer> inDegree = new HashMap<>();
        for (String n : allNodes) inDegree.put(n, 0);
        for (Map.Entry<String, Set<String>> e : dependents.entrySet()) {
            for (String dep : e.getValue()) {
                inDegree.merge(dep, 1, Integer::sum);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (String n : allNodes) {
            if (inDegree.get(n) == 0) queue.add(n);
        }

        List<String> order = new ArrayList<>(allNodes.size());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            order.add(current);
            for (String successor : dependents.getOrDefault(current, Set.of())) {
                int remaining = inDegree.merge(successor, -1, Integer::sum);
                if (remaining == 0) queue.add(successor);
            }
        }

        if (order.size() != allNodes.size()) {
            // Not all nodes were processed â†’ cycle exists; find it for the error message
            Set<String> inCycle = new HashSet<>(allNodes);
            inCycle.removeAll(order);
            throw new CyclicDependencyException(inCycle);
        }

        return order;
    }

    // â”€â”€ Exception â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Thrown when the dependency graph contains a cycle, making deterministic evaluation impossible. */
    public static final class CyclicDependencyException extends RuntimeException {
        private final Set<String> involvedNodes;

        CyclicDependencyException(Set<String> involvedNodes) {
            super("Cyclic dependency detected among nodes: " + involvedNodes);
            this.involvedNodes = Collections.unmodifiableSet(new HashSet<>(involvedNodes));
        }

        /** The set of node keys involved in the cycle. */
        public Set<String> involvedNodes() { return involvedNodes; }
    }
}

