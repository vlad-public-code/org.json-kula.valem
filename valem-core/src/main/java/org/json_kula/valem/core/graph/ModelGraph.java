package org.json_kula.valem.core.graph;

import java.util.List;

/**
 * A serialisable projection of a {@link CompiledModel}'s {@link DependencyGraph} for the client's
 * "Why is this number?" provenance + dependency-graph surface (docs/sandbox/why-this-number.md).
 *
 * <p>This is a <b>read-only lens</b> over structure the engine already computes — no state, no
 * evaluation, no persistence. It is produced on demand by {@link GraphProjection#project} and is a
 * pure function of the compiled spec.
 *
 * <p>Node keys are the graph's own canonical, {@code $.}-prefixed form
 * ({@code "$.order.total"}, {@code "$.order.total#minimum"}, {@code "$constraint:<id>"},
 * {@code "$effect:<id>"}) — see {@link GraphProjection#canonicalKey} for the one function that maps
 * a client-supplied path to this form.
 *
 * @param modelId the model this graph belongs to
 * @param nodes   every node: base field, derivation, meta-derivation, constraint, effect
 * @param edges   dependency edges, {@code from} feeds {@code to} (i.e. {@code to} reads {@code from})
 * @param levels  nodes grouped by topological depth (shallowest first); layout for the DAG view
 */
public record ModelGraph(
        String modelId,
        List<Node> nodes,
        List<Edge> edges,
        List<List<String>> levels) {

    /**
     * One node in the graph.
     *
     * @param key        canonical node key (see class doc)
     * @param kind       display kind: {@code BASE | DERIVED | META | CONSTRAINT | EFFECT}
     * @param label      human-facing label (a constraint's message, else the de-prefixed path)
     * @param expression the JSONata source that computes this node, or {@code null} for base fields
     */
    public record Node(String key, String kind, String label, String expression) {}

    /** A dependency edge: {@code to} reads from {@code from}, so {@code to} recomputes when {@code from} changes. */
    public record Edge(String from, String to) {}
}
