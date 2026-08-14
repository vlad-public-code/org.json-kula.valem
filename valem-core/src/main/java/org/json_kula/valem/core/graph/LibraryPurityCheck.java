package org.json_kula.valem.core.graph;

import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds references in a library definition that would read the <em>model document</em>.
 *
 * <h2>Why this exists</h2>
 * A library function cannot reach the consuming expression's document by any route: its closures
 * were built during the definition's own evaluation, against the definition's own input (JSON
 * {@code null}), and calling one from a consumer expression does not re-root it. A bare field
 * reference, {@code $}, {@code $$}, a path-step call {@code order.$g()}, and a context-injecting
 * {@code -} signature all yield nothing.
 *
 * <p>That is what makes a library call free of dependency-graph consequences — {@code
 * ExpressionPathExtractor} cannot see into a callee, so a library that <em>could</em> read the
 * document would create unrecorded dependencies and silently stale derived values.
 *
 * <p>But "yields nothing" is a <b>silent</b> failure. A definition like
 * {@code $netTotal := function() { order.subtotal - order.discount }} compiles cleanly, its call
 * site validates cleanly, and the derived field is simply blank — with no error in the spec, the
 * trace, or the logs. This check turns that silence into a located error naming the fix. It is a
 * diagnostic, not an enforcement: the runtime already guarantees the isolation.
 *
 * <h2>The rule</h2>
 * Positional, not per-node-type: a reference is a document read when it is evaluated against the
 * definition's own input, rather than against something already rooted at a variable, a lambda
 * parameter, or an enclosing map step. So {@code order.total} is rejected while
 * {@code $const.brackets.(upTo - band)} — whose {@code upTo}/{@code band} are fields of the mapped
 * element — is fine. {@code $$} is rejected at any depth, because it re-roots from anywhere.
 */
public final class LibraryPurityCheck {

    private LibraryPurityCheck() {}

    /** One document-rooted reference: how it is written, for the error message. */
    public record Finding(String reference, String hint) {}

    /**
     * Returns every document-rooted reference in {@code define}, in encounter order and de-duplicated.
     * An unparseable definition yields an empty list — its parse error is reported separately.
     */
    public static List<Finding> check(String define) {
        AstNode ast;
        try {
            ast = Parser.parse(define);
        } catch (Exception e) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Finding> out = new ArrayList<>();
        walk(ast, true, seen, out);
        return out;
    }

    private static void report(String reference, String hint, Set<String> seen, List<Finding> out) {
        if (seen.add(reference)) out.add(new Finding(reference, hint));
    }

    private static final String FIELD_HINT =
            "pass the value in as an argument at the call site";
    private static final String CONTEXT_HINT =
            "a library has no input document; take what you need as a parameter";

    /**
     * @param documentRooted true while the current expression is evaluated against the definition's
     *                       own input; false once navigation is rooted at a variable or a mapped element
     */
    private static void walk(AstNode node, boolean documentRooted, Set<String> seen, List<Finding> out) {
        if (node == null) return;

        switch (node) {
            // ── The document-rooted reads ────────────────────────────────────────
            case AstNode.FieldRef fr -> {
                if (documentRooted) report(fr.name(), FIELD_HINT, seen, out);
            }
            case AstNode.ContextRef ignored -> {
                if (documentRooted) report("$", CONTEXT_HINT, seen, out);
            }
            // $$ re-roots at the document from any depth — the one unconditional rejection.
            case AstNode.RootRef ignored -> report("$$", CONTEXT_HINT, seen, out);
            case AstNode.DescendantStep ignored -> {
                if (documentRooted) report("**", CONTEXT_HINT, seen, out);
            }
            case AstNode.WildcardStep ignored -> {
                if (documentRooted) report("*", CONTEXT_HINT, seen, out);
            }

            case AstNode.PathExpr pe -> walkPath(pe.steps(), documentRooted, seen, out);

            // ── Structure ────────────────────────────────────────────────────────
            case AstNode.Lambda lam -> walk(lam.body(), documentRooted, seen, out);
            case AstNode.LambdaCall lc -> {
                walk(lc.lambda(), documentRooted, seen, out);
                lc.args().forEach(a -> walk(a, documentRooted, seen, out));
            }
            case AstNode.Block blk -> blk.expressions().forEach(e -> walk(e, documentRooted, seen, out));
            case AstNode.VariableBinding vb -> walk(vb.value(), documentRooted, seen, out);
            case AstNode.Parenthesized p -> walk(p.inner(), documentRooted, seen, out);
            case AstNode.ForceArray fa -> walk(fa.source(), documentRooted, seen, out);

            case AstNode.FunctionCall fc -> fc.args().forEach(a -> walk(a, documentRooted, seen, out));
            case AstNode.PartialApplication pa -> pa.args().forEach(a -> walk(a, documentRooted, seen, out));

            case AstNode.BinaryOp bo -> {
                walk(bo.left(), documentRooted, seen, out);
                walk(bo.right(), documentRooted, seen, out);
            }
            case AstNode.UnaryMinus um -> walk(um.operand(), documentRooted, seen, out);
            case AstNode.ElvisExpr ev -> {
                walk(ev.left(), documentRooted, seen, out);
                walk(ev.right(), documentRooted, seen, out);
            }
            case AstNode.CoalesceExpr co -> {
                walk(co.left(), documentRooted, seen, out);
                walk(co.right(), documentRooted, seen, out);
            }
            case AstNode.RangeExpr re -> {
                walk(re.from(), documentRooted, seen, out);
                walk(re.to(), documentRooted, seen, out);
            }
            case AstNode.ConditionalExpr ce -> {
                walk(ce.condition(), documentRooted, seen, out);
                walk(ce.then(), documentRooted, seen, out);
                walk(ce.otherwise(), documentRooted, seen, out);
            }
            case AstNode.ArrayConstructor ac -> ac.elements().forEach(e -> walk(e, documentRooted, seen, out));
            case AstNode.ObjectConstructor oc -> oc.pairs().forEach(p -> {
                walk(p.key(), documentRooted, seen, out);
                walk(p.value(), documentRooted, seen, out);
            });

            case AstNode.PredicateExpr pe -> {
                walk(pe.source(), documentRooted, seen, out);
                // The predicate is evaluated against an element of the source, never the input.
                walk(pe.predicate(), false, seen, out);
            }
            case AstNode.ArraySubscript as -> {
                walk(as.source(), documentRooted, seen, out);
                walk(as.index(), false, seen, out);
            }
            case AstNode.SortExpr se -> {
                walk(se.source(), documentRooted, seen, out);
                se.keys().forEach(k -> walk(k.key(), false, seen, out));
            }
            case AstNode.GroupByExpr gb -> {
                walk(gb.source(), documentRooted, seen, out);
                gb.pairs().forEach(p -> {
                    walk(p.key(), false, seen, out);
                    walk(p.value(), false, seen, out);
                });
            }
            case AstNode.ChainExpr ce -> ce.steps().forEach(s -> walk(s, documentRooted, seen, out));
            case AstNode.TransformExpr te -> {
                walk(te.source(), documentRooted, seen, out);
                walk(te.pattern(), false, seen, out);
                walk(te.update(), false, seen, out);
                walk(te.delete(), false, seen, out);
            }
            case AstNode.TransformLambda tl -> {
                walk(tl.pattern(), false, seen, out);
                walk(tl.update(), false, seen, out);
                walk(tl.delete(), false, seen, out);
            }

            // ── Leaves with no document dependency ───────────────────────────────
            case AstNode.NumberLiteral ignored -> { }
            case AstNode.StringLiteral ignored -> { }
            case AstNode.BooleanLiteral ignored -> { }
            case AstNode.NullLiteral ignored -> { }
            case AstNode.RegexLiteral ignored -> { }
            case AstNode.VariableRef ignored -> { }
            case AstNode.ParentStep ignored -> { }
            case AstNode.PartialPlaceholder ignored -> { }
            case AstNode.ContextBinding ignored -> { }
            case AstNode.PositionBinding ignored -> { }
        }
    }

    /**
     * Walks the steps of a path. Only the <b>head</b> decides whether the path reads the document: a
     * head rooted at a variable ({@code $const.brackets}, {@code $table[…]}, {@code ($const.x)[0]})
     * roots everything after it at that value, so the tail steps — and any map block or predicate
     * inside them — are not document reads.
     *
     * <p>A document-rooted path is reported <b>whole</b> ({@code order.subtotal}), not one finding
     * per segment: the author wrote one reference and needs to see it as they wrote it.
     */
    private static void walkPath(List<AstNode> steps, boolean documentRooted,
                                 Set<String> seen, List<Finding> out) {
        if (steps.isEmpty()) return;

        AstNode head = steps.getFirst();
        List<AstNode> tail = steps.subList(1, steps.size());

        if (documentRooted && head instanceof AstNode.FieldRef) {
            report(renderPath(steps), FIELD_HINT, seen, out);
            // The tail navigates within that reference; still walk any predicate/map bodies in it,
            // which are separate expressions rather than part of the dotted name.
            for (AstNode step : tail) {
                if (!(step instanceof AstNode.FieldRef)) walk(step, false, seen, out);
            }
            return;
        }

        walk(head, documentRooted, seen, out);
        boolean tailRooted = documentRooted && !rootsAtValue(head);
        for (AstNode step : tail) walk(step, tailRooted, seen, out);
    }

    /**
     * True when this head roots the rest of the path at a value rather than at the input document —
     * a variable, a call result, a constructed array/object, or any of those wrapped in parentheses,
     * a predicate or a subscript.
     */
    private static boolean rootsAtValue(AstNode head) {
        return switch (head) {
            case AstNode.VariableRef ignored       -> true;
            case AstNode.FunctionCall ignored      -> true;
            case AstNode.LambdaCall ignored        -> true;
            case AstNode.ArrayConstructor ignored  -> true;
            case AstNode.ObjectConstructor ignored -> true;
            case AstNode.Parenthesized p           -> rootsAtValue(p.inner());
            case AstNode.Block b                   -> !b.expressions().isEmpty()
                                                      && rootsAtValue(b.expressions().getLast());
            case AstNode.PathExpr pe               -> !pe.steps().isEmpty()
                                                      && rootsAtValue(pe.steps().getFirst());
            case AstNode.PredicateExpr pe          -> rootsAtValue(pe.source());
            case AstNode.ArraySubscript as         -> rootsAtValue(as.source());
            case AstNode.ForceArray fa             -> rootsAtValue(fa.source());
            case AstNode.SortExpr se               -> rootsAtValue(se.source());
            default -> false;
        };
    }

    /** Renders a document-rooted path as the author wrote it, e.g. {@code order.subtotal}. */
    private static String renderPath(List<AstNode> steps) {
        StringBuilder sb = new StringBuilder();
        for (AstNode step : steps) {
            if (step instanceof AstNode.FieldRef fr) {
                if (!sb.isEmpty()) sb.append('.');
                sb.append(fr.name());
            } else {
                break;              // stop at the first non-name step; the dotted prefix identifies it
            }
        }
        return sb.toString();
    }
}
