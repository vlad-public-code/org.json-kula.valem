package org.json_kula.valem.core.graph;

import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.MetaDerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a {@link ModelSpec} into a {@link CompiledModel} by:
 * <ol>
 *   <li>Parsing every derivation / meta-derivation / constraint expression via
 *       {@link ExpressionPathExtractor} to extract field path dependencies.</li>
 *   <li>Building a {@link DependencyGraph} from those dependencies.</li>
 *   <li>Verifying the graph is acyclic (throws {@link DependencyGraph.CyclicDependencyException}
 *       on violation).</li>
 * </ol>
 *
 * <p>This class is stateless; use {@link #compile(ModelSpec)}.
 */
public final class ModelSpecCompiler {

    private ModelSpecCompiler() {}

    /**
     * Compiles the given spec.
     *
     * @throws DependencyGraph.CyclicDependencyException if derivations form a cycle
     * @throws IllegalArgumentException                  if any expression cannot be parsed
     */
    public static CompiledModel compile(ModelSpec spec) {
        DependencyGraph.Builder graphBuilder = DependencyGraph.builder();

        Map<String, DerivationSpec>     derivationByPath    = new LinkedHashMap<>();
        Map<String, MetaDerivationSpec> metaDerivationByKey = new LinkedHashMap<>();

        // â"€â"€ Derivations â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
        for (DerivationSpec d : spec.derivations()) {
            String targetPath = d.path();
            graphBuilder.addNode(targetPath, DependencyGraph.NodeKind.DERIVED);
            derivationByPath.put(targetPath, d);

            // For wildcard paths (e.g. $.items[*].lineTotal), pass the element-parent prefix
            // so that $parent variable references in the expression resolve correctly.
            String parentPrefix = wildcardParentPrefix(targetPath);
            Set<String> readPaths = ExpressionPathExtractor.extract(d.expr(), parentPrefix);
            for (String dep : readPaths) {
                // dep may be a base field or another derived field — add as BASE by default;
                // the kind is upgraded when that path's own derivation is registered
                graphBuilder.addNode(dep, DependencyGraph.NodeKind.BASE);
                graphBuilder.addEdge(dep, targetPath);
            }

            // If the expression reads from a path that is itself derived, the graph already
            // captures that dependency through the edge chain — no extra work needed.
        }

        // â"€â"€ Meta-Derivations â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
        for (MetaDerivationSpec md : spec.metaDerivations()) {
            String nodeKey = md.nodeKey(); // "path#property"
            graphBuilder.addNode(nodeKey, DependencyGraph.NodeKind.META);
            metaDerivationByKey.put(nodeKey, md);

            Set<String> readPaths = ExpressionPathExtractor.extract(md.expr());
            for (String dep : readPaths) {
                graphBuilder.addNode(dep, DependencyGraph.NodeKind.BASE);
                graphBuilder.addEdge(dep, nodeKey);
            }
        }

        // â"€â"€ Constraints â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
        // Constraint expressions are not computed values (no target node), but they do
        // read from fields. We extract their dependencies so that the runtime can schedule
        // constraint checks after the relevant nodes settle.
        // We register a synthetic node keyed by the constraint id so that dirty propagation
        // can reach constraints via the graph.
        for (ConstraintSpec c : spec.constraints()) {
            String constraintKey = "$constraint:" + c.id();
            graphBuilder.addNode(constraintKey, DependencyGraph.NodeKind.META);

            Set<String> readPaths = ExpressionPathExtractor.extract(c.expr());
            for (String dep : readPaths) {
                graphBuilder.addNode(dep, DependencyGraph.NodeKind.BASE);
                graphBuilder.addEdge(dep, constraintKey);
            }
        }

        // ── Effects ────────────────────────────────────────────────────────────
        // Register a synthetic node per effect so dirty propagation determines when the effect's
        // trigger needs re-evaluation. The effect re-fires when its trigger or dedupeKey inputs
        // change; statusPath writes are deliberately not edges (they must not re-trigger the effect).
        for (org.json_kula.valem.core.model.EffectSpec e : spec.effects()) {
            String effectKey = "$effect:" + e.id();
            graphBuilder.addNode(effectKey, DependencyGraph.NodeKind.META);

            Set<String> readPaths = new java.util.LinkedHashSet<>(ExpressionPathExtractor.extract(e.trigger()));
            if (e.dedupeKey() != null && !e.dedupeKey().isBlank()) {
                readPaths.addAll(ExpressionPathExtractor.extract(e.dedupeKey()));
            }
            if (e.requests() != null && !e.requests().isBlank()) {
                // Fan-out: re-emit when the data the request list is built from changes.
                readPaths.addAll(ExpressionPathExtractor.extract(e.requests()));
            }
            for (String expr : new String[]{ e.prompt(), e.at(), e.afterMs() }) {
                if (expr != null && !expr.isBlank()) readPaths.addAll(ExpressionPathExtractor.extract(expr));
            }
            for (String dep : readPaths) {
                graphBuilder.addNode(dep, DependencyGraph.NodeKind.BASE);
                graphBuilder.addEdge(dep, effectKey);
            }
        }

        DependencyGraph graph = graphBuilder.build();

        return new CompiledModel(
                spec,
                graph,
                derivationByPath,
                metaDerivationByKey,
                List.copyOf(spec.constraints()));
    }

    /**
     * Returns the element-parent prefix for a wildcard derivation path, or {@code ""}
     * for non-wildcard paths.
     *
     * <p>Example: {@code "$.items[*].lineTotal"} → {@code "$.items[*]"}
     * (the path up to but not including the last dot-segment).
     */
    private static String wildcardParentPrefix(String path) {
        if (!path.contains("[*]")) return "";
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(0, lastDot) : path;
    }
}

