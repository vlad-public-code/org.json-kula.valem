package org.json_kula.valem.core.graph;

import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.model.DefaultValueSpec;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.MetaDerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the JSONata expression strings a {@link ModelSpec} will need compiled — every
 * derivation, meta-derivation, constraint, default-value, and effect expression.
 *
 * <p>Used to <b>batch-warm</b> an {@link org.json_kula.valem.core.engine.ExpressionCache} at model
 * registration time (see {@code ExpressionCache.warm}) so a model's expressions compile in one javac
 * invocation instead of one per expression on first access.
 *
 * <p>The result is a best-effort superset for warming, not an authoritative validation list: any
 * expression not collected here simply compiles lazily on first use, so completeness affects only
 * the batch speedup, never correctness. View expressions are not collected here — this module cannot
 * see the view records — so callers that render views add {@code ViewExpressions.collect} (valem-view)
 * to the same warm batch.
 */
public final class SpecExpressions {

    private SpecExpressions() {}

    /** All compile-eligible expression strings in {@code spec}, blanks removed, in encounter order. */
    public static List<String> collect(ModelSpec spec) {
        List<String> out = new ArrayList<>();
        for (DerivationSpec d : spec.derivations())        add(out, d.expr());
        for (MetaDerivationSpec md : spec.metaDerivations()) add(out, md.expr());
        for (ConstraintSpec c : spec.constraints())        add(out, c.expr());
        for (DefaultValueSpec dv : spec.defaultValues())   add(out, dv.expr());
        for (EffectSpec e : spec.effects()) {
            add(out, e.trigger());
            add(out, e.dedupeKey());
            add(out, e.prompt());
            add(out, e.at());
            add(out, e.afterMs());
            add(out, e.requests());
            add(out, e.body());                                    // target write-link body
            if (e.request() != null) add(out, e.request().body()); // outbound HTTP request body
            if (e.payload() != null) e.payload().values().forEach(v -> add(out, v));
            // response.set reads the runtime-only $response binding and is evaluated by the effect
            // shell's own cache, not the model's — but compiled instances live in the process-wide
            // shared map, so warming them here still saves the shell its javac. Without it a timer's
            // first fire compiled its (tic-tac-toe: 6 KB) expression one javac at a time, on the
            // single virtual-thread carrier, while every other request waited.
            if (e.responseSet() != null) e.responseSet().values().forEach(v -> add(out, v));
        }
        return out;
    }

    private static void add(List<String> out, String expr) {
        if (expr != null && !expr.isBlank()) {
            out.add(expr);
        }
    }
}
