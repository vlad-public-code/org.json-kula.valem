package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.translator.ScopeAnalyzer;
import org.json_kula.valem.core.engine.LibraryCache;
import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.model.DefaultValueSpec;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.LibrarySpec;
import org.json_kula.valem.core.model.MetaDerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers two questions about a spec's library: <i>what does it export</i>, and <i>where is an
 * export referenced</i>. Shared by {@link ModelSpecValidator} (unknown-name resolution, shadowing)
 * and {@link SpecEvolution} (the drop guard).
 */
public final class LibraryExports {

    private LibraryExports() {}

    /**
     * Every name {@code library} exports, or an empty set when it declares none or fails to compile.
     *
     * <p>Compiling is the only exact answer — a definition's export list is its own result and may be
     * computed rather than literal. It goes through the shared {@link LibraryCache}, so a spec whose
     * library the validator or runtime has already compiled costs a hash and a lookup. A compile
     * failure yields an empty set rather than propagating: the caller is asking about exports, and
     * the compile error is reported separately, with a better message, by {@code checkLibrary}.
     */
    public static Set<String> namesOf(LibrarySpec library) {
        return namesOf(library, JsonNodeFactory.instance.objectNode());
    }

    /** As {@link #namesOf(LibrarySpec)}, with the constants the layers are defined against. */
    public static Set<String> namesOf(LibrarySpec library, ObjectNode constants) {
        if (library == null || library.layers().isEmpty()) return Set.of();
        try {
            return LibraryCache.compile(library.layers(), constants).names();
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** Every name the spec's library exports, compiled against that spec's own constants. */
    public static Set<String> namesOf(ModelSpec spec) {
        return namesOf(spec.library(), constantsNodeOf(spec));
    }

    /** The spec's constants as the object node they are bound as ({@code $const}). */
    public static ObjectNode constantsNodeOf(ModelSpec spec) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        spec.constants().forEach(node::set);
        return node;
    }

    /**
     * For each of {@code names}, the spec locations whose expressions reference it as a free
     * variable. Names nothing references are absent from the result.
     *
     * <p>Free-variable resolution rather than a textual scan: it is exact, and it does not mistake a
     * block-local {@code $name := …} inside one expression for a library call.
     */
    public static Map<String, List<String>> referenceLocations(ModelSpec spec, List<String> names) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (names.isEmpty()) return out;
        Set<String> wanted = new LinkedHashSet<>(names);

        forEachExpression(spec, (location, expr) -> {
            for (String name : freeNames(expr)) {
                if (wanted.contains(name)) {
                    out.computeIfAbsent(name, k -> new ArrayList<>()).add(location);
                }
            }
        });
        return out;
    }

    /**
     * The free {@code $name}s of one expression — those it neither binds itself nor receives as a
     * lambda parameter. Built-ins are <b>not</b> filtered; the caller decides what counts as
     * provided. An unparseable expression yields an empty set (its parse error is reported by the
     * validator's own compile pass).
     */
    public static Set<String> freeNames(String expr) {
        if (expr == null || expr.isBlank()) return Set.of();
        try {
            return ScopeAnalyzer.freeVariables(Parser.parse(expr));
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** True when {@code name} is a JSONata built-in and therefore always resolvable. */
    public static boolean isBuiltin(String name) {
        return Parser.isBuiltin(name);
    }

    /** Receives every {@code (location, expression)} pair in a spec. */
    public interface ExpressionVisitor {
        void accept(String location, String expr);
    }

    /**
     * Visits every JSONata expression a spec carries, with the location string the validator uses.
     * View expressions are excluded — they are structural JSON evaluated by {@code valem-view}, and
     * are linted there.
     */
    public static void forEachExpression(ModelSpec spec, ExpressionVisitor visitor) {
        int i = 0;
        for (DerivationSpec d : spec.derivations()) {
            visit(visitor, "derivations[" + i++ + "] (" + d.path() + ")", d.expr());
        }
        i = 0;
        for (MetaDerivationSpec md : spec.metaDerivations()) {
            visit(visitor, "metaDerivations[" + i++ + "]", md.expr());
        }
        i = 0;
        for (ConstraintSpec c : spec.constraints()) {
            visit(visitor, "constraints[" + i++ + "] (" + c.id() + ")", c.expr());
        }
        i = 0;
        for (DefaultValueSpec dv : spec.defaultValues()) {
            visit(visitor, "defaultValues[" + i++ + "] (" + dv.path() + ")", dv.expr());
        }
        i = 0;
        for (EffectSpec e : spec.effects()) {
            String loc = "effects[" + i++ + "] (" + e.id() + ")";
            visit(visitor, loc, e.trigger());
            visit(visitor, loc, e.dedupeKey());
            visit(visitor, loc, e.prompt());
            visit(visitor, loc, e.at());
            visit(visitor, loc, e.afterMs());
            visit(visitor, loc, e.requests());
            visit(visitor, loc, e.body());
            if (e.request() != null) visit(visitor, loc, e.request().body());
            if (e.payload() != null) e.payload().values().forEach(v -> visit(visitor, loc, v));
        }
    }

    private static void visit(ExpressionVisitor visitor, String location, String expr) {
        if (expr != null && !expr.isBlank()) visitor.accept(location, expr);
    }
}
