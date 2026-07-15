package org.json_kula.valem.core.graph;

import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a JSONata expression AST and collects all field path references the expression reads.
 *
 * <p>Paths use JsonPath notation with {@code [*]} for array-wildcard steps,
 * e.g. {@code "$.order.items[*].qty"}, {@code "$.order.customer.creditLimit"}.
 * These paths drive the dependency graph: when any returned path changes,
 * the expression must be re-evaluated.
 *
 * <p>For wildcard-derivation expressions that use {@code $parent} to refer to the
 * current array element, pass the element-context pattern as {@code parentPrefix}
 * (e.g. {@code "$.items[*]"} for a derivation at {@code "$.items[*].lineTotal"}).
 * {@code $parent.price} then resolves to {@code "$.items[*].price"}.
 */
public final class ExpressionPathExtractor {

    private ExpressionPathExtractor() {}

    /**
     * Parses {@code expr} and returns all field paths it reads.
     * Equivalent to {@code extract(expr, "")}.
     *
     * @throws IllegalArgumentException if the expression cannot be parsed
     */
    public static Set<String> extract(String expr) {
        return extract(expr, "");
    }

    /**
     * Parses {@code expr} and returns all field paths it reads.
     *
     * <p>When {@code parentPrefix} is non-empty (e.g. {@code "$.items[*]"}), any
     * {@code $parent} variable reference in a path expression is resolved relative
     * to that prefix: {@code $parent.price} → {@code $.items[*].price}.
     *
     * @param parentPrefix element-context prefix for {@code $parent} resolution;
     *                     pass {@code ""} (or use the single-arg overload) for non-wildcard
     * @throws IllegalArgumentException if the expression cannot be parsed
     */
    public static Set<String> extract(String expr, String parentPrefix) {
        AstNode ast;
        try {
            ast = Parser.parse(expr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse JSONata expression: " + expr, e);
        }
        Set<String> paths = new LinkedHashSet<>();
        walk(ast, "", parentPrefix, paths);
        return Set.copyOf(paths);
    }

    // ── Main walker ────────────────────────────────────────────────────────────

    /**
     * @param prefix       accumulated path so far (empty string = top-level context)
     * @param parentPrefix resolved prefix for {@code $parent} variable references
     */
    private static void walk(AstNode node, String prefix, String parentPrefix, Set<String> out) {
        if (node == null) return;

        switch (node) {

            case AstNode.FieldRef fr -> out.add(join(prefix, fr.name()));

            case AstNode.PathExpr pe -> walkPathSteps(pe.steps(), prefix, parentPrefix, out);

            case AstNode.WildcardStep ignored -> {
                // Bare * means "all fields" — record the current array path if any
                if (!prefix.isEmpty()) out.add(prefix + "[*]");
            }

            case AstNode.ArraySubscript as ->
                // source[N]: source drives the dependency; numeric index is not a path
                walk(as.source(), prefix, parentPrefix, out);

            case AstNode.PredicateExpr pe -> {
                // source[predicate]: walk source, then predicate in its own scope
                walk(pe.source(), prefix, parentPrefix, out);
                walk(pe.predicate(), "", parentPrefix, out);
            }

            case AstNode.ForceArray fa -> walk(fa.source(), prefix, parentPrefix, out);

            case AstNode.Parenthesized p -> walk(p.inner(), prefix, parentPrefix, out);

            // Operators
            case AstNode.BinaryOp bo -> {
                walk(bo.left(),  prefix, parentPrefix, out);
                walk(bo.right(), prefix, parentPrefix, out);
            }

            case AstNode.UnaryMinus um -> walk(um.operand(), prefix, parentPrefix, out);

            case AstNode.ElvisExpr ev -> {
                walk(ev.left(),  "", parentPrefix, out);
                walk(ev.right(), "", parentPrefix, out);
            }

            case AstNode.CoalesceExpr co -> {
                walk(co.left(),  "", parentPrefix, out);
                walk(co.right(), "", parentPrefix, out);
            }

            case AstNode.RangeExpr re -> {
                walk(re.from(), "", parentPrefix, out);
                walk(re.to(),   "", parentPrefix, out);
            }

            case AstNode.ConditionalExpr ce -> {
                walk(ce.condition(), "", parentPrefix, out);
                walk(ce.then(),      "", parentPrefix, out);
                if (ce.otherwise() != null) walk(ce.otherwise(), "", parentPrefix, out);
            }

            // Functions
            case AstNode.FunctionCall fc ->
                fc.args().forEach(arg -> walk(arg, "", parentPrefix, out));

            case AstNode.PartialApplication pa ->
                pa.args().forEach(arg -> walk(arg, "", parentPrefix, out));

            case AstNode.LambdaCall lc -> {
                walk(lc.lambda().body(), "", parentPrefix, out);
                lc.args().forEach(arg -> walk(arg, "", parentPrefix, out));
            }

            case AstNode.Lambda lam -> walk(lam.body(), "", parentPrefix, out);

            // Blocks / bindings / constructors
            case AstNode.Block blk ->
                blk.expressions().forEach(e -> walk(e, prefix, parentPrefix, out));

            case AstNode.VariableBinding vb -> walk(vb.value(), "", parentPrefix, out);

            case AstNode.ObjectConstructor oc ->
                oc.pairs().forEach(p -> {
                    walk(p.key(),   "", parentPrefix, out);
                    walk(p.value(), "", parentPrefix, out);
                });

            case AstNode.ArrayConstructor ac ->
                ac.elements().forEach(e -> walk(e, "", parentPrefix, out));

            // Sort / group / chain / transform
            case AstNode.SortExpr se -> {
                walk(se.source(), "", parentPrefix, out);
                se.keys().forEach(k -> walk(k.key(), "", parentPrefix, out));
            }

            case AstNode.GroupByExpr gb -> {
                walk(gb.source(), "", parentPrefix, out);
                gb.pairs().forEach(p -> {
                    walk(p.key(),   "", parentPrefix, out);
                    walk(p.value(), "", parentPrefix, out);
                });
            }

            case AstNode.ChainExpr ce ->
                ce.steps().forEach(s -> walk(s, "", parentPrefix, out));

            case AstNode.TransformExpr te -> {
                walk(te.source(),  "", parentPrefix, out);
                walk(te.pattern(), "", parentPrefix, out);
                walk(te.update(),  "", parentPrefix, out);
                if (te.delete() != null) walk(te.delete(), "", parentPrefix, out);
            }

            case AstNode.TransformLambda tl -> {
                walk(tl.pattern(), "", parentPrefix, out);
                walk(tl.update(),  "", parentPrefix, out);
                if (tl.delete() != null) walk(tl.delete(), "", parentPrefix, out);
            }

            // Leaf nodes — no field dependencies
            case AstNode.NumberLiteral     ignored -> {}
            case AstNode.StringLiteral     ignored -> {}
            case AstNode.BooleanLiteral    ignored -> {}
            case AstNode.NullLiteral       ignored -> {}
            case AstNode.RegexLiteral      ignored -> {}
            case AstNode.ContextRef        ignored -> {}
            case AstNode.RootRef           ignored -> {}
            case AstNode.VariableRef       ignored -> {}
            case AstNode.DescendantStep    ignored -> {}
            case AstNode.ParentStep        ignored -> {}
            case AstNode.PartialPlaceholder ignored -> {}
            case AstNode.ContextBinding    ignored -> {}
            case AstNode.PositionBinding   ignored -> {}
        }
    }

    // ── Path-step walker ──────────────────────────────────────────────────────

    /**
     * Walks the steps of a {@link AstNode.PathExpr} sequentially, threading the
     * accumulated path prefix from one step to the next.
     *
     * <p>A {@link AstNode.Parenthesized} or {@link AstNode.Block} step means
     * "map over the current array elements" — the prefix gains {@code [*]} and
     * inner FieldRefs produce paths like {@code order.items[*].qty}.
     *
     * <p>A {@link AstNode.VariableRef} with name {@code "parent"} resolves to
     * {@code parentPrefix}, enabling expressions like {@code $parent.price} to be
     * correctly mapped to {@code $.items[*].price} when the derivation path is
     * {@code $.items[*].lineTotal}.
     */
    private static void walkPathSteps(List<AstNode> steps, String prefix,
                                      String parentPrefix, Set<String> out) {
        if (steps.isEmpty()) return;

        AstNode head = steps.getFirst();
        List<AstNode> tail = steps.subList(1, steps.size());

        switch (head) {

            case AstNode.FieldRef fr -> {
                String next = join(prefix, fr.name());
                if (tail.isEmpty()) out.add(next);
                else walkPathSteps(tail, next, parentPrefix, out);
            }

            case AstNode.WildcardStep ignored -> {
                String next = prefix + "[*]";
                if (tail.isEmpty()) out.add(next);
                else walkPathSteps(tail, next, parentPrefix, out);
            }

            case AstNode.VariableRef vr when "parent".equals(vr.name()) -> {
                // $parent refers to the element at parentPrefix (e.g. "$.items[*]")
                if (!tail.isEmpty()) walkPathSteps(tail, parentPrefix, parentPrefix, out);
                // $parent alone produces no field-path dependency
            }

            case AstNode.PredicateExpr pe -> {
                // source[predicate] as a path step — compute new prefix based on source + filter
                String next = predicateStepPrefix(pe, prefix, parentPrefix, out);
                if (tail.isEmpty()) out.add(next);
                else walkPathSteps(tail, next, parentPrefix, out);
            }

            case AstNode.ArraySubscript as -> {
                // source[N] — navigate into source; numeric index doesn't broaden the path
                String next = subscriptStepPrefix(as, prefix, parentPrefix, out);
                if (tail.isEmpty()) out.add(next);
                else walkPathSteps(tail, next, parentPrefix, out);
            }

            case AstNode.Parenthesized p -> {
                // (expr) inside a PathExpr = map over elements → prefix + [*]
                String elementCtx = prefix + "[*]";
                walk(p.inner(), elementCtx, parentPrefix, out);
                if (!tail.isEmpty()) walkPathSteps(tail, elementCtx, parentPrefix, out);
            }

            case AstNode.Block blk -> {
                String elementCtx = prefix + "[*]";
                blk.expressions().forEach(e -> walk(e, elementCtx, parentPrefix, out));
                if (!tail.isEmpty()) walkPathSteps(tail, elementCtx, parentPrefix, out);
            }

            // Variable-binding steps do not advance the path
            case AstNode.ContextBinding ignored -> {
                if (!tail.isEmpty()) walkPathSteps(tail, prefix, parentPrefix, out);
            }

            case AstNode.PositionBinding ignored -> {
                if (!tail.isEmpty()) walkPathSteps(tail, prefix, parentPrefix, out);
            }

            default -> {
                walk(head, prefix, parentPrefix, out);
                if (!tail.isEmpty()) walkPathSteps(tail, prefix, parentPrefix, out);
            }
        }
    }

    /**
     * Resolves a {@link AstNode.PredicateExpr} used as a PathExpr step.
     * Returns the new prefix after navigating through the predicate.
     * Side-effects: may add paths to {@code out} (e.g. for predicate field refs).
     */
    private static String predicateStepPrefix(AstNode.PredicateExpr pe, String prefix,
                                              String parentPrefix, Set<String> out) {
        String sourcePath;
        if (pe.source() instanceof AstNode.FieldRef fr) {
            sourcePath = join(prefix, fr.name());
        } else {
            // Complex source — walk it normally; fall back to current prefix
            walk(pe.source(), prefix, parentPrefix, out);
            sourcePath = prefix;
        }

        switch (pe.predicate()) {
            case AstNode.WildcardStep ignored -> {
                // items[*] — explicit wildcard
                return sourcePath + "[*]";
            }
            case AstNode.NumberLiteral ignored -> {
                // items[0] — specific index; treat source itself as the dependency
                out.add(sourcePath);
                return sourcePath;
            }
            default -> {
                // items[type = "x"] — walk predicate for its field refs
                walk(pe.predicate(), "", parentPrefix, out);
                return sourcePath + "[*]";
            }
        }
    }

    /**
     * Resolves an {@link AstNode.ArraySubscript} used as a PathExpr step.
     */
    private static String subscriptStepPrefix(AstNode.ArraySubscript as, String prefix,
                                              String parentPrefix, Set<String> out) {
        if (as.source() instanceof AstNode.FieldRef fr) {
            String sourcePath = join(prefix, fr.name());
            out.add(sourcePath);
            return sourcePath;
        }
        walk(as.source(), prefix, parentPrefix, out);
        return prefix;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Appends {@code name} to {@code prefix} with a {@code .} separator, adding {@code $.} root for top-level paths. */
    private static String join(String prefix, String name) {
        return prefix.isEmpty() ? "$." + name : prefix + "." + name;
    }
}
