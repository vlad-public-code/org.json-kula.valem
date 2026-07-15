package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.MetaDerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compiled, immutable view of a {@link ModelSpec}.
 *
 * <p>Produced by {@link ModelSpecCompiler}. Holds all runtime-ready artefacts:
 * the dependency graph, lookup maps from path/key to spec objects, and the
 * evaluation order for reactive re-evaluation after mutations.
 */
public final class CompiledModel {

    private final ModelSpec spec;
    private final DependencyGraph graph;

    // Lookup: derived path â†’ DerivationSpec
    private final Map<String, DerivationSpec> derivationByPath;

    // Lookup: nodeKey (e.g. "order.downPayment#minimum") â†’ MetaDerivationSpec
    private final Map<String, MetaDerivationSpec> metaDerivationByKey;

    // Constraints in declaration order (evaluated after each mutation)
    private final List<ConstraintSpec> constraints;

    // Named constants materialized into a single object node, bound as $const in every expression.
    private final ObjectNode constantsNode;

    // Bounded LRU of resolved static schema fragments keyed by concrete field path (audit CPU-10):
    // repeated reads/mutations of the same field skip the SchemaPaths navigation + deep copy. Values
    // are the canonical resolved fragment; callers get a deep copy so they may mutate it freely.
    private static final int SCHEMA_CACHE_MAX = 2_048;
    private final Map<String, ObjectNode> staticSchemaCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ObjectNode> eldest) {
                    return size() > SCHEMA_CACHE_MAX;
                }
            });

    CompiledModel(
            ModelSpec spec,
            DependencyGraph graph,
            Map<String, DerivationSpec> derivationByPath,
            Map<String, MetaDerivationSpec> metaDerivationByKey,
            List<ConstraintSpec> constraints) {
        this.spec                = spec;
        this.graph               = graph;
        this.derivationByPath    = Collections.unmodifiableMap(derivationByPath);
        this.metaDerivationByKey = Collections.unmodifiableMap(metaDerivationByKey);
        this.constraints         = Collections.unmodifiableList(constraints);
        this.constantsNode       = buildConstantsNode(spec);
    }

    private static ObjectNode buildConstantsNode(ModelSpec spec) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        spec.constants().forEach(node::set);
        return node;
    }

    // â”€â”€ Accessors â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public ModelSpec spec()                    { return spec; }
    public DependencyGraph graph()             { return graph; }
    public List<ConstraintSpec> constraints()  { return constraints; }

    /**
     * Returns the named constants as a single object node, ready to bind as {@code $const} in a
     * JSONata evaluation. Always non-null (empty object when no constants are declared).
     */
    public ObjectNode constantsNode()          { return constantsNode; }

    /**
     * Returns the {@link DerivationSpec} for the given field path, or {@code null}
     * if the path is a base (writable) field.
     */
    public DerivationSpec derivationFor(String path) {
        return derivationByPath.get(path);
    }

    /**
     * Returns the {@link MetaDerivationSpec} for the given node key
     * (format: {@code "path#property_name"}), or {@code null} if none.
     */
    public MetaDerivationSpec metaDerivationFor(String nodeKey) {
        return metaDerivationByKey.get(nodeKey);
    }

    /** All derivation paths (keys of derived fields). */
    public java.util.Set<String> derivedPaths() { return derivationByPath.keySet(); }

    /** All meta derivation node keys. */
    public java.util.Set<String> metaNodeKeys() { return metaDerivationByKey.keySet(); }

    /** Matches a concrete array-index segment, e.g. the {@code [7]} in {@code $.items[7].qty}. */
    private static final java.util.regex.Pattern ARRAY_INDEX = java.util.regex.Pattern.compile("\\[\\d+]");

    /**
     * Returns the static JSON Schema fragment for {@code fieldPath} (local {@code $ref}s resolved),
     * memoized per model. The returned node is a fresh deep copy — safe for the caller to overlay
     * live meta values onto without disturbing the cache or the spec schema (audit CPU-10).
     *
     * <p>Concrete array indices are normalized to the wildcard {@code [*]} for the cache key: the
     * static schema of every element of an array is identical ({@code SchemaPaths} resolves
     * {@code items[0]} and {@code items[*]} the same way), so keying by the concrete index would give a
     * 5,000-element array 5,000 duplicate entries and thrash the bounded LRU on the mutation hot path.
     */
    public ObjectNode staticSchema(String fieldPath) {
        String key = ARRAY_INDEX.matcher(fieldPath).replaceAll("[*]");
        ObjectNode cached = staticSchemaCache.computeIfAbsent(
                key, k -> SchemaPaths.resolve(spec.schema(), k));
        return cached.deepCopy();
    }
}

