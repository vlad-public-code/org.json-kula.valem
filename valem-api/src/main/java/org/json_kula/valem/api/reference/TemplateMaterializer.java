package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.model.DefaultValueSpec;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.LineageEntry;
import org.json_kula.valem.core.model.MetaDerivationSpec;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Flattens a <b>branch</b> ({@code template: { ref }}) into a self-contained, inlined effective spec
 * (references design §5). Resolves the template chain through the {@link ModelResolver}, applies the
 * branch's sections over the resolved ancestors (nearest ancestor wins, then the branch wins), and
 * pins a {@code lineage} (ref + version + digest + repo + owner per ancestor). After materialization
 * the model depends on neither the template nor its repository — the determinism invariant (§1).
 *
 * <p>Each effect carries an {@code origin} (its contributing ancestor + owner) via the lineage, feeding
 * inherited-effect approval in M5.
 */
public class TemplateMaterializer {

    private final ModelResolver resolver;

    public TemplateMaterializer(ModelResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * If {@code branch} declares a {@code template}, returns the flattened, lineage-pinned effective
     * spec; otherwise returns {@code branch} unchanged.
     *
     * @throws ReferenceException.UnresolvedReference if any ancestor coordinate resolves nowhere
     * @throws ReferenceException.TemplateCycle       if the lineage is not acyclic
     */
    public ModelSpec materialize(ModelSpec branch) {
        if (branch.template() == null || branch.template().ref() == null) {
            return branch;
        }

        // Resolve the ancestor chain, oldest last (branch.template first, then its template, …).
        List<ResolvedSpec> chain = resolveChain(branch);

        // Fold from the oldest ancestor down to the branch: each layer overrides the accumulated base.
        // chain is nearest-first, so iterate it in reverse (oldest first), then apply the branch last.
        ModelSpec acc = chain.get(chain.size() - 1).spec();
        for (int i = chain.size() - 2; i >= 0; i--) {
            acc = mergeOver(acc, chain.get(i).spec());
        }
        acc = mergeOver(acc, branch);

        List<EffectSpec> stamped = stampOrigins(acc.effects(), chain, branch);
        List<LineageEntry> lineage = buildLineage(chain);
        return rebuild(acc, branch.id(), lineage, stamped);
    }

    /**
     * Stamps each inherited effect with its provenance {@code origin { fromRef, fromOwner }} — the
     * nearest ancestor that defined it. Branch-authored effects get a {@code null} origin (the brancher
     * owns them). This is what inherited-effect approval (M5) keys on to decide whether an effect
     * crossed an ownership boundary.
     */
    private static List<EffectSpec> stampOrigins(List<EffectSpec> effects, List<ResolvedSpec> chain,
                                                 ModelSpec branch) {
        Map<String, EffectSpec.Origin> origin = new java.util.HashMap<>();
        // Farthest ancestor first so the nearest ancestor that (re)defines an id wins.
        for (int i = chain.size() - 1; i >= 0; i--) {
            ModelCoordinate coord = chain.get(i).resolved();
            EffectSpec.Origin o = new EffectSpec.Origin(coord.identity(), coord.namespace());
            for (EffectSpec e : chain.get(i).spec().effects()) origin.put(e.id(), o);
        }
        Set<String> branchAuthored = new LinkedHashSet<>();
        for (EffectSpec e : branch.effects()) branchAuthored.add(e.id());

        List<EffectSpec> out = new ArrayList<>(effects.size());
        for (EffectSpec e : effects) {
            out.add(branchAuthored.contains(e.id()) ? e.withOrigin(null) : e.withOrigin(origin.get(e.id())));
        }
        return out;
    }

    /** Resolves the transitive template chain, nearest-first, detecting cycles and missing ancestors. */
    private List<ResolvedSpec> resolveChain(ModelSpec branch) {
        List<ResolvedSpec> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(coordIdentity(branch.id()));

        String ref = branch.template().ref();
        while (ref != null) {
            ModelCoordinate coord = ModelCoordinate.parse(ref);
            if (!seen.add(coord.identity())) {
                throw new ReferenceException.TemplateCycle(
                        "template lineage is cyclic at '" + coord.identity() + "'");
            }
            ResolvedSpec resolved = resolver.resolveSpec(coord).orElseThrow(() ->
                    new ReferenceException.UnresolvedReference(
                            "template '" + coord + "' resolves to no repository"));
            chain.add(resolved);
            ModelSpec ancestor = resolved.spec();
            ref = (ancestor.template() != null) ? ancestor.template().ref() : null;
        }
        return chain;
    }

    /** Pins one lineage entry per resolved ancestor (nearest-first order preserved). */
    private static List<LineageEntry> buildLineage(List<ResolvedSpec> chain) {
        List<LineageEntry> lineage = new ArrayList<>(chain.size());
        for (ResolvedSpec rs : chain) {
            lineage.add(new LineageEntry(
                    rs.resolved().identity(),
                    rs.resolved().version() instanceof ModelCoordinate.Exact ex ? ex.version().toString() : null,
                    rs.digest(),
                    rs.repoId(),
                    ownerOf(rs.resolved())));
        }
        return lineage;
    }

    /** Owner = the coordinate namespace (the tenant boundary); null for a namespace-less local model. */
    private static String ownerOf(ModelCoordinate coord) {
        return coord.namespace();
    }

    /**
     * Merges {@code overlay} over {@code base}: overlay's section entries override the base's by key,
     * new entries are appended. Produces a spec carrying <em>overlay's</em> identity/version/tests/view;
     * schema is overlay's unless it is an empty object (then inherited from base); constants deep-merge
     * (overlay wins per key).
     */
    private static ModelSpec mergeOver(ModelSpec base, ModelSpec overlay) {
        JsonNode schema = isEmptyObject(overlay.schema()) ? base.schema() : overlay.schema();
        JsonNode view = overlay.viewDefinition() != null ? overlay.viewDefinition() : base.viewDefinition();

        Map<String, JsonNode> constants = new LinkedHashMap<>(base.constants());
        constants.putAll(overlay.constants());

        return ModelSpec.of(
                overlay.id(),
                overlay.version(),
                schema,
                mergeByKey(base.derivations(), overlay.derivations(), DerivationSpec::path),
                mergeByKey(base.metaDerivations(), overlay.metaDerivations(),
                        m -> m.path() + "#" + m.property().name()),
                mergeByKey(base.constraints(), overlay.constraints(), ConstraintSpec::id),
                overlay.tests().isEmpty() ? base.tests() : overlay.tests(),
                mergeByKey(base.defaultValues(), overlay.defaultValues(), DefaultValueSpec::path),
                constants,
                view,
                mergeByKey(base.effects(), overlay.effects(), EffectSpec::id),
                null,   // template resolved away
                null,   // lineage set at the end
                null, null);
    }

    /** Overlay entries replace base entries with the same key (base order preserved), new ones appended. */
    private static <T> List<T> mergeByKey(List<T> base, List<T> overlay, Function<T, String> keyFn) {
        Map<String, T> byKey = new LinkedHashMap<>();
        for (T b : base) byKey.put(keyFn.apply(b), b);
        for (T o : overlay) byKey.put(keyFn.apply(o), o);
        return new ArrayList<>(byKey.values());
    }

    /** Rebuilds the effective spec with the branch's id, origin-stamped effects, and pinned lineage. */
    private static ModelSpec rebuild(ModelSpec merged, String branchId, List<LineageEntry> lineage,
                                     List<EffectSpec> effects) {
        return ModelSpec.of(
                branchId, merged.version(), merged.schema(),
                merged.derivations(), merged.metaDerivations(), merged.constraints(),
                merged.tests(), merged.defaultValues(), merged.constants(), merged.viewDefinition(),
                effects,
                null,        // template inlined away — the model is now self-contained
                lineage,
                null, null);
    }

    private static boolean isEmptyObject(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || (n.isObject() && n.isEmpty());
    }

    private static String coordIdentity(String id) {
        return ModelCoordinate.isValid(id) ? ModelCoordinate.parse(id).identity() : id;
    }
}
