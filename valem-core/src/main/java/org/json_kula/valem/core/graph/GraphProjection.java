package org.json_kula.valem.core.graph;

import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.MetaDerivationSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects a {@link CompiledModel} into a serialisable {@link ModelGraph} for the client's
 * provenance + dependency-graph surface (docs/sandbox/why-this-number.md, §2 of the tech spec).
 *
 * <p>Everything here is already computed by the engine — the {@link DependencyGraph} (nodes, edges,
 * levels) and the per-node source expressions on the spec. This class only reshapes them:
 * <ul>
 *   <li>widens the graph's {@code BASE/DERIVED/META} kinds to the display vocabulary
 *       {@code BASE/DERIVED/META/CONSTRAINT/EFFECT}, splitting the synthetic {@code $constraint:} /
 *       {@code $effect:} nodes (which the graph stores as {@code META}) out by key prefix;</li>
 *   <li>attaches each node's JSONata source expression;</li>
 *   <li>reverses the graph's {@code dependenciesOf} into explicit {@code from→to} edges.</li>
 * </ul>
 *
 * <p>Pure and side-effect free: no state read, no evaluation, no mutation of the compiled model.
 */
public final class GraphProjection {

    private static final String CONSTRAINT_PREFIX = "$constraint:";
    private static final String EFFECT_PREFIX     = "$effect:";

    private GraphProjection() {}

    /** Builds the {@link ModelGraph} lens for {@code model}. */
    public static ModelGraph project(CompiledModel model, String modelId) {
        DependencyGraph graph = model.graph();

        Map<String, ConstraintSpec> constraintById = new LinkedHashMap<>();
        for (ConstraintSpec c : model.constraints()) constraintById.put(c.id(), c);

        Map<String, EffectSpec> effectById = new LinkedHashMap<>();
        for (EffectSpec e : model.spec().effects()) effectById.put(e.id(), e);

        List<ModelGraph.Node> nodes = new ArrayList<>(graph.nodes().size());
        for (String key : graph.nodes()) {
            nodes.add(projectNode(key, graph, model, constraintById, effectById));
        }

        List<ModelGraph.Edge> edges = new ArrayList<>();
        for (String key : graph.nodes()) {
            for (String dependency : graph.dependenciesOf(key)) {
                edges.add(new ModelGraph.Edge(dependency, key));
            }
        }

        return new ModelGraph(modelId, nodes, edges, graph.evaluationLevels());
    }

    private static ModelGraph.Node projectNode(
            String key,
            DependencyGraph graph,
            CompiledModel model,
            Map<String, ConstraintSpec> constraintById,
            Map<String, EffectSpec> effectById) {

        if (key.startsWith(CONSTRAINT_PREFIX)) {
            ConstraintSpec c = constraintById.get(key.substring(CONSTRAINT_PREFIX.length()));
            String label = (c != null && c.message() != null && !c.message().isBlank())
                    ? c.message()
                    : key.substring(CONSTRAINT_PREFIX.length());
            return new ModelGraph.Node(key, "CONSTRAINT", label, c != null ? c.expr() : null);
        }

        if (key.startsWith(EFFECT_PREFIX)) {
            EffectSpec e = effectById.get(key.substring(EFFECT_PREFIX.length()));
            String label = key.substring(EFFECT_PREFIX.length());
            return new ModelGraph.Node(key, "EFFECT", label, e != null ? e.trigger() : null);
        }

        DependencyGraph.NodeInfo info = graph.nodeInfo(key);
        DependencyGraph.NodeKind kind = info != null ? info.kind() : DependencyGraph.NodeKind.BASE;
        switch (kind) {
            case DERIVED -> {
                DerivationSpec d = model.derivationFor(key);
                return new ModelGraph.Node(key, "DERIVED", label(key), d != null ? d.expr() : null);
            }
            case META -> {
                MetaDerivationSpec md = model.metaDerivationFor(key);
                return new ModelGraph.Node(key, "META", label(key), md != null ? md.expr() : null);
            }
            default -> {
                return new ModelGraph.Node(key, "BASE", label(key), null);
            }
        }
    }

    /** Human-facing label fallback: the de-prefixed path (the DAG/popover shows this when no richer label exists). */
    private static String label(String key) {
        return canonicalToDisplay(key);
    }

    /**
     * Maps a client-supplied field path to the canonical node-key form used in {@link ModelGraph}.
     *
     * <p>Graph node keys are {@code $.}-prefixed ({@code "$.order.total"}); a client hover target
     * (a view leaf's {@code bind()} path) may arrive with or without that prefix, or dot-led. This is
     * the single normalisation function (F16) the tech spec pins — the TypeScript client mirrors it.
     * Synthetic {@code $constraint:} / {@code $effect:} keys and already-{@code $.}-prefixed paths pass
     * through unchanged.
     */
    public static String canonicalKey(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.startsWith(CONSTRAINT_PREFIX) || path.startsWith(EFFECT_PREFIX)) return path;
        if (path.startsWith("$.")) return path;
        if (path.startsWith(".")) return "$" + path;          // ".total" → "$.total"
        return "$." + path;                                    // "order.total" → "$.order.total"
    }

    /** Inverse of {@link #canonicalKey} for display: strips the leading {@code $.} (keeps {@code #meta} suffixes). */
    static String canonicalToDisplay(String key) {
        return key.startsWith("$.") ? key.substring(2) : key;
    }
}
