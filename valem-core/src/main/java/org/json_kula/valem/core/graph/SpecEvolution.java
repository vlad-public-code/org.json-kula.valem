package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.model.DefaultValueSpec;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.MetaDerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.PathConverter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Declarative incremental update to a {@link ModelSpec}.
 *
 * <p>Each list in the diff is optional. Entries are applied in three phases per section:
 * <ol>
 *   <li><b>Remove</b> entries whose id/path/nodeKey appears in {@code remove*}.</li>
 *   <li><b>Update</b> entries whose id/path/nodeKey matches (full replacement).</li>
 *   <li><b>Add</b> entries not present in the existing spec.</li>
 * </ol>
 *
 * <p>The evolved spec is then validated and recompiled. If validation or cycle-detection
 * fails the caller should reject the evolution.
 */
public record SpecEvolution(
        String newVersion,
        List<String>            removeDerivations,
        List<DerivationSpec>    upsertDerivations,
        List<String>            removeConstraints,
        List<ConstraintSpec>    upsertConstraints,
        List<String>            removeMetaDerivations,
        List<MetaDerivationSpec> upsertMetaDerivations,
        List<String>            removeDefaultValues,
        List<DefaultValueSpec>  upsertDefaultValues,
        Map<String, JsonNode>   newConstants,       // full replacement of the constants map (null = keep)
        JsonNode                newSchema,
        JsonNode                newViewDefinition,
        Map<String, JsonNode>   backfill,          // $.path → value to seed on instances lacking it
        List<String>            removeEffects,
        List<EffectSpec>        upsertEffects,
        Map<String, JsonNode>   upsertSchemaDefs,   // $defs name → definition schema (wholesale per name)
        List<String>            removeSchemaDefs,   // $defs names to drop (rejected if still referenced)
        List<SchemaNodeUpsert>  upsertSchemaNodes,  // data-path → subschema (wholesale replace at node)
        List<String>            removeSchemaNodes,  // data-paths to drop from the schema
        String                  expectedVersion,    // optimistic-concurrency precondition (null = no check)
        String                  newDefaultView,     // set viewDefinition.defaultView (null = keep)
        List<JsonNode>          upsertViews,        // full ViewSpec payloads, keyed by embedded "id"
        List<String>            removeViews,        // view ids to drop
        List<ComponentUpsert>   upsertComponents,   // per-view component upserts (by component id)
        List<ComponentRemove>   removeComponents,   // per-view component removals (by component id)
        Map<String, JsonNode>   upsertConstants,    // constant name → value (wholesale per name)
        List<String>            removeConstants     // constant names to drop (rejected if referenced)
) {
    /** A component upsert: place {@code component} within {@code viewId} at the given location. */
    public record ComponentUpsert(String viewId, String parentId, String beforeId, JsonNode component) {
        @JsonCreator
        public static ComponentUpsert of(
                @JsonProperty("viewId")    String viewId,
                @JsonProperty("parentId")  String parentId,
                @JsonProperty("beforeId")  String beforeId,
                @JsonProperty("component") JsonNode component) {
            return new ComponentUpsert(viewId, parentId, beforeId, component);
        }
    }

    /** A component removal: drop {@code componentId} (and its subtree) from {@code viewId}. */
    public record ComponentRemove(String viewId, String componentId) {
        @JsonCreator
        public static ComponentRemove of(
                @JsonProperty("viewId")      String viewId,
                @JsonProperty("componentId") String componentId) {
            return new ComponentRemove(viewId, componentId);
        }
    }
    /**
     * A single schema-node upsert: replace the subschema at a canonical data address
     * ({@code path}) wholesale with {@code schema}, optionally adjusting the parent's
     * {@code required} membership ({@code required}: {@code true} adds, {@code false} removes,
     * {@code null} leaves it alone).
     */
    public record SchemaNodeUpsert(String path, JsonNode schema, Boolean required) {
        @JsonCreator
        public static SchemaNodeUpsert of(
                @JsonProperty("path")     String path,
                @JsonProperty("schema")   JsonNode schema,
                @JsonProperty("required") Boolean required) {
            return new SchemaNodeUpsert(path, schema, required);
        }
    }

    @JsonCreator
    public static SpecEvolution of(
            @JsonProperty("newVersion")            String newVersion,
            @JsonProperty("removeDerivations")     List<String>             removeDerivations,
            @JsonProperty("upsertDerivations")     List<DerivationSpec>     upsertDerivations,
            @JsonProperty("removeConstraints")     List<String>             removeConstraints,
            @JsonProperty("upsertConstraints")     List<ConstraintSpec>     upsertConstraints,
            @JsonProperty("removeMetaDerivations") List<String>             removeMetaDerivations,
            @JsonProperty("upsertMetaDerivations") List<MetaDerivationSpec> upsertMetaDerivations,
            @JsonProperty("removeDefaultValues")   List<String>             removeDefaultValues,
            @JsonProperty("upsertDefaultValues")   List<DefaultValueSpec>   upsertDefaultValues,
            @JsonProperty("newConstants")          Map<String, JsonNode>    newConstants,
            @JsonProperty("newSchema")             JsonNode                 newSchema,
            @JsonProperty("newViewDefinition")     JsonNode                 newViewDefinition,
            @JsonProperty("backfill")              Map<String, JsonNode>    backfill,
            @JsonProperty("removeEffects")         List<String>             removeEffects,
            @JsonProperty("upsertEffects")         List<EffectSpec>         upsertEffects,
            @JsonProperty("upsertSchemaDefs")      Map<String, JsonNode>    upsertSchemaDefs,
            @JsonProperty("removeSchemaDefs")      List<String>             removeSchemaDefs,
            @JsonProperty("upsertSchemaNodes")     List<SchemaNodeUpsert>   upsertSchemaNodes,
            @JsonProperty("removeSchemaNodes")     List<String>             removeSchemaNodes,
            @JsonProperty("expectedVersion")       String                   expectedVersion,
            @JsonProperty("newDefaultView")        String                   newDefaultView,
            @JsonProperty("upsertViews")           List<JsonNode>           upsertViews,
            @JsonProperty("removeViews")           List<String>             removeViews,
            @JsonProperty("upsertComponents")      List<ComponentUpsert>    upsertComponents,
            @JsonProperty("removeComponents")      List<ComponentRemove>    removeComponents,
            @JsonProperty("upsertConstants")       Map<String, JsonNode>    upsertConstants,
            @JsonProperty("removeConstants")       List<String>             removeConstants) {
        return new SpecEvolution(
                newVersion,
                removeDerivations     != null ? List.copyOf(removeDerivations)     : List.of(),
                upsertDerivations     != null ? List.copyOf(upsertDerivations)     : List.of(),
                removeConstraints     != null ? List.copyOf(removeConstraints)     : List.of(),
                upsertConstraints     != null ? List.copyOf(upsertConstraints)     : List.of(),
                removeMetaDerivations != null ? List.copyOf(removeMetaDerivations) : List.of(),
                upsertMetaDerivations != null ? List.copyOf(upsertMetaDerivations) : List.of(),
                removeDefaultValues   != null ? List.copyOf(removeDefaultValues)   : List.of(),
                upsertDefaultValues   != null ? List.copyOf(upsertDefaultValues)   : List.of(),
                newConstants,   // null = keep existing; a (possibly empty) map fully replaces
                // Treat an explicit JSON null (a NullNode, produced when the field round-trips through
                // serialization) as "absent" — otherwise newSchema/newViewDefinition would read as
                // non-null and, e.g., falsely trip the "newSchema cannot be combined with schema diff"
                // guard when only a schema-node diff was intended.
                absentIfNull(newSchema),
                absentIfNull(newViewDefinition),
                backfill              != null ? Map.copyOf(backfill)               : Map.of(),
                removeEffects         != null ? List.copyOf(removeEffects)         : List.of(),
                upsertEffects         != null ? List.copyOf(upsertEffects)         : List.of(),
                // Preserve insertion order and allow null values to be absent; do not deep-copy
                // (the nodes are treated as immutable inputs and only read during the splice).
                upsertSchemaDefs      != null ? new LinkedHashMap<>(upsertSchemaDefs) : Map.of(),
                removeSchemaDefs      != null ? List.copyOf(removeSchemaDefs)      : List.of(),
                upsertSchemaNodes     != null ? List.copyOf(upsertSchemaNodes)     : List.of(),
                removeSchemaNodes     != null ? List.copyOf(removeSchemaNodes)     : List.of(),
                expectedVersion,
                newDefaultView,
                upsertViews           != null ? List.copyOf(upsertViews)           : List.of(),
                removeViews           != null ? List.copyOf(removeViews)           : List.of(),
                upsertComponents      != null ? List.copyOf(upsertComponents)      : List.of(),
                removeComponents      != null ? List.copyOf(removeComponents)      : List.of(),
                upsertConstants       != null ? new LinkedHashMap<>(upsertConstants) : Map.of(),
                removeConstants       != null ? List.copyOf(removeConstants)       : List.of());
    }

    /** Normalizes an explicit JSON {@code null} ({@link JsonNode#isNull()}) to a Java {@code null}. */
    private static JsonNode absentIfNull(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    /** True when this evolution changes the schema (wholesale or via any diff tier). */
    public boolean touchesSchema() {
        return newSchema != null || hasSchemaDiff();
    }

    /**
     * Applies this diff to {@code base} and returns the evolved spec.
     *
     * @throws IllegalArgumentException if validation of the evolved spec fails
     */
    public ModelSpec applyTo(ModelSpec base) {
        JsonNode schema         = evolveSchema(base.schema());
        JsonNode viewDefinition = evolveView(base.viewDefinition());
        String   version        = newVersion        != null ? newVersion        : base.version();

        List<DerivationSpec>     derivations     = evolveList(
                base.derivations(), upsertDerivations, removeDerivations, DerivationSpec::path);
        List<MetaDerivationSpec> metaDerivations = evolveList(
                base.metaDerivations(), upsertMetaDerivations, removeMetaDerivations,
                md -> md.path() + "#" + md.property().name().toLowerCase());
        List<ConstraintSpec>     constraints     = evolveList(
                base.constraints(), upsertConstraints, removeConstraints, ConstraintSpec::id);
        List<DefaultValueSpec>   defaultValues   = evolveList(
                base.defaultValues(), upsertDefaultValues, removeDefaultValues, DefaultValueSpec::path);
        List<EffectSpec>         effects         = evolveList(
                base.effects(), upsertEffects, removeEffects, EffectSpec::id);
        Map<String, JsonNode>    constants       = evolveConstants(base);

        ModelSpec evolved = ModelSpec.of(
                base.id(), version, schema,
                derivations, metaDerivations, constraints,
                base.tests(), defaultValues, constants, viewDefinition,
                effects,
                base.template(),   // carry the branch parent forward
                base.lineage(),    // carry pinned provenance forward
                null,   // initialState (removed)
                null);  // actions (removed)

        ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(evolved);
        if (!validation.isValid()) {
            String summary = validation.errors().stream()
                    .map(e -> e.location() + ": " + e.message())
                    .collect(Collectors.joining("; "));
            throw new SpecEvolutionException(
                    "Evolved spec failed validation: " + summary, validation.errors());
        }

        return evolved;
    }

    /**
     * Thrown by {@link #applyTo} when the <em>evolved</em> spec fails validation, carrying the exact
     * {@link ModelSpecValidator.ValidationError} list so callers (the generation loop's convergence
     * gate) can count errors structurally instead of parsing the message string. Extends
     * {@link IllegalArgumentException} so existing {@code catch (IllegalArgumentException)} sites —
     * which also handle the evolution-shape violations thrown as plain {@code IllegalArgumentException}
     * — keep working unchanged.
     */
    public static final class SpecEvolutionException extends IllegalArgumentException {
        private final transient List<ModelSpecValidator.ValidationError> errors;

        public SpecEvolutionException(String message,
                                      List<ModelSpecValidator.ValidationError> errors) {
            super(message);
            this.errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public List<ModelSpecValidator.ValidationError> errors() {
            return errors;
        }
    }

    // ── Schema evolution ─────────────────────────────────────────────────────────

    private boolean hasSchemaDiff() {
        return !upsertSchemaDefs.isEmpty() || !removeSchemaDefs.isEmpty()
                || !upsertSchemaNodes.isEmpty() || !removeSchemaNodes.isEmpty();
    }

    /**
     * Applies the schema tiers to {@code baseSchema} and returns the evolved schema node.
     *
     * <p>Exactly one addressing mode may be used per evolution: the wholesale {@code newSchema}
     * <em>or</em> the definition/node diff fields, never both. Definition upserts/removes and
     * node upserts/removes may combine freely.
     *
     * @throws IllegalArgumentException on any evolution-shape violation
     */
    private JsonNode evolveSchema(JsonNode baseSchema) {
        boolean diff = hasSchemaDiff();
        if (newSchema != null && diff) {
            throw new IllegalArgumentException(
                    "SpecEvolution: newSchema cannot be combined with schema diff fields "
                    + "(upsertSchemaDefs/removeSchemaDefs/upsertSchemaNodes/removeSchemaNodes)");
        }
        if (newSchema != null) return newSchema;
        if (!diff)             return baseSchema;

        ObjectNode schema = baseSchema != null && baseSchema.isObject()
                ? baseSchema.deepCopy()
                : JsonNodeFactory.instance.objectNode();

        // 1. Definition upserts (wholesale replace by name)
        if (!upsertSchemaDefs.isEmpty()) {
            ObjectNode defs = childObject(schema, "$defs");
            upsertSchemaDefs.forEach((name, def) -> {
                if (def == null || def.isNull()) {
                    throw new IllegalArgumentException(
                            "SpecEvolution: upsertSchemaDefs['" + name + "'] must be a schema object");
                }
                defs.set(name, def);
            });
        }

        // 2. Node removals (property + parent 'required' entry, or array 'items')
        for (String path : removeSchemaNodes) {
            requireCanonicalNodePath(path, "removeSchemaNodes");
            Slot slot = navigateToParent(schema, path, false);
            if (slot == null || !slot.removeChild()) {
                throw new IllegalArgumentException(
                        "SpecEvolution: removeSchemaNodes path '" + path + "' is absent from the schema");
            }
        }

        // 3. Node upserts (wholesale replace at path; create intermediates; apply 'required')
        for (SchemaNodeUpsert up : upsertSchemaNodes) {
            requireCanonicalNodePath(up.path(), "upsertSchemaNodes");
            if (up.schema() == null || up.schema().isNull() || !up.schema().isObject()) {
                throw new IllegalArgumentException(
                        "SpecEvolution: upsertSchemaNodes['" + up.path() + "'].schema must be a schema object");
            }
            Slot slot = navigateToParent(schema, up.path(), true);
            slot.setChild(up.schema());
            if (up.required() != null) slot.setRequired(up.required());
        }

        // 4. Definition removals (structural), then a still-referenced sweep over the result
        if (!removeSchemaDefs.isEmpty()) {
            JsonNode defsNode = schema.get("$defs");
            ObjectNode defs = defsNode != null && defsNode.isObject() ? (ObjectNode) defsNode : null;
            for (String name : removeSchemaDefs) {
                if (defs != null) defs.remove(name);
            }
            Map<String, List<String>> refs = collectRefLocations(schema);
            for (String name : removeSchemaDefs) {
                List<String> locations = refs.get(name);
                if (locations != null && !locations.isEmpty()) {
                    throw new IllegalArgumentException(
                            "SpecEvolution: removeSchemaDefs cannot drop '" + name
                            + "' — still referenced at " + String.join(", ", locations));
                }
            }
        }

        return schema;
    }

    private static void requireCanonicalNodePath(String path, String field) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("SpecEvolution: " + field + " requires a non-blank path");
        }
        if ("$".equals(path.trim())) {
            throw new IllegalArgumentException(
                    "SpecEvolution: " + field + " cannot target the root '$' — use newSchema to replace the whole schema");
        }
        if (!PathConverter.isCanonicalAddress(path)) {
            throw new IllegalArgumentException(
                    "SpecEvolution: " + field + " path '" + path + "' is not a canonical address; use '"
                    + PathConverter.toCanonicalAddress(path) + "'");
        }
    }

    /** Ensures {@code parent[key]} is an object and returns it, creating it when absent. */
    private static ObjectNode childObject(ObjectNode parent, String key) {
        JsonNode existing = parent.get(key);
        if (existing != null && existing.isObject()) return (ObjectNode) existing;
        ObjectNode created = parent.objectNode();
        parent.set(key, created);
        return created;
    }

    /**
     * Walks {@code path}'s intermediate segments to the schema node that directly owns the
     * terminal segment, without resolving through any {@code $ref}. Crossing a {@code $ref}
     * on the way is a ref-boundary violation. Returns {@code null} when {@code create} is false
     * and an intermediate is absent.
     */
    private static Slot navigateToParent(ObjectNode rootSchema, String path, boolean create) {
        List<String> segs = PathConverter.toSegments(path);
        ObjectNode cursor = rootSchema;
        for (int i = 0; i < segs.size() - 1; i++) {
            refBoundaryGuard(cursor, path);
            ObjectNode next = descend(cursor, segs.get(i), create);
            if (next == null) return null;
            cursor = next;
        }
        refBoundaryGuard(cursor, path);
        return new Slot(cursor, segs.getLast());
    }

    private static void refBoundaryGuard(ObjectNode cursor, String path) {
        if (cursor.has("$ref")) {
            String name = SchemaPaths.localDefName(cursor.get("$ref"));
            throw new IllegalArgumentException(
                    "SpecEvolution: node path '" + path + "' traverses a $ref"
                    + (name != null ? " (#/$defs/" + name + ")" : "")
                    + " — upserting through a $ref would change every usage of the definition. "
                    + "Upsert the definition (upsertSchemaDefs) or replace the referencing node with an inline schema first.");
        }
    }

    /** Descends one object-property or array-element step; creates the container when {@code create}. */
    private static ObjectNode descend(ObjectNode cursor, String seg, boolean create) {
        if (SchemaPaths.isArraySegment(seg)) {
            JsonNode items = cursor.get("items");
            if (items != null && items.isObject()) return (ObjectNode) items;
            if (!create) return null;
            return childObject(cursor, "items");
        }
        ObjectNode props = null;
        JsonNode propsNode = cursor.get("properties");
        if (propsNode != null && propsNode.isObject()) props = (ObjectNode) propsNode;
        if (props == null) {
            if (!create) return null;
            props = childObject(cursor, "properties");
        }
        JsonNode child = props.get(seg);
        if (child != null && child.isObject()) return (ObjectNode) child;
        if (!create) return null;
        return childObject(props, seg);
    }

    /** Collects, for every local {@code $ref} in {@code schema}, the def name → list of locations. */
    private static Map<String, List<String>> collectRefLocations(JsonNode schema) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        collectRefLocations(schema, "$", out);
        return out;
    }

    private static void collectRefLocations(JsonNode node, String loc, Map<String, List<String>> out) {
        if (node == null) return;
        if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node) collectRefLocations(child, loc + "[" + i++ + "]", out);
            return;
        }
        if (!node.isObject()) return;
        JsonNode ref = node.get("$ref");
        if (ref != null) {
            String name = SchemaPaths.localDefName(ref);
            if (name != null) out.computeIfAbsent(name, k -> new ArrayList<>()).add(loc);
        }
        node.fields().forEachRemaining(e -> collectRefLocations(e.getValue(), loc + "." + e.getKey(), out));
    }

    /** A parent schema node plus the terminal object-property name / array-element marker to edit. */
    private record Slot(ObjectNode parent, String key) {

        private boolean isArrayElement() { return SchemaPaths.isArraySegment(key); }

        void setChild(JsonNode subschema) {
            if (isArrayElement()) {
                parent.set("items", subschema);
            } else {
                childObjectOn(parent, "properties").set(key, subschema);
            }
        }

        boolean removeChild() {
            if (isArrayElement()) {
                return parent.remove("items") != null;
            }
            JsonNode props = parent.get("properties");
            if (props == null || !props.isObject() || !props.has(key)) return false;
            ((ObjectNode) props).remove(key);
            removeRequired();
            return true;
        }

        void setRequired(boolean required) {
            if (isArrayElement()) return;  // 'required' is meaningless for an array element
            com.fasterxml.jackson.databind.node.ArrayNode req = requiredArray(true);
            boolean present = false;
            for (JsonNode n : req) if (n.isTextual() && n.asText().equals(key)) { present = true; break; }
            if (required && !present) {
                req.add(key);
            } else if (!required && present) {
                for (int i = req.size() - 1; i >= 0; i--) {
                    if (req.get(i).isTextual() && req.get(i).asText().equals(key)) req.remove(i);
                }
            }
        }

        private void removeRequired() {
            com.fasterxml.jackson.databind.node.ArrayNode req = requiredArray(false);
            if (req == null) return;
            for (int i = req.size() - 1; i >= 0; i--) {
                if (req.get(i).isTextual() && req.get(i).asText().equals(key)) req.remove(i);
            }
        }

        private com.fasterxml.jackson.databind.node.ArrayNode requiredArray(boolean create) {
            JsonNode existing = parent.get("required");
            if (existing != null && existing.isArray()) {
                return (com.fasterxml.jackson.databind.node.ArrayNode) existing;
            }
            if (!create) return null;
            com.fasterxml.jackson.databind.node.ArrayNode arr = parent.arrayNode();
            parent.set("required", arr);
            return arr;
        }

        private static ObjectNode childObjectOn(ObjectNode parent, String key) {
            JsonNode existing = parent.get(key);
            if (existing != null && existing.isObject()) return (ObjectNode) existing;
            ObjectNode created = parent.objectNode();
            parent.set(key, created);
            return created;
        }
    }

    // ── Constants evolution ──────────────────────────────────────────────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper CONST_SCAN_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private Map<String, JsonNode> evolveConstants(ModelSpec base) {
        boolean diff = !upsertConstants.isEmpty() || !removeConstants.isEmpty();
        if (newConstants != null && diff) {
            throw new IllegalArgumentException(
                    "SpecEvolution: newConstants cannot be combined with upsertConstants/removeConstants");
        }
        if (newConstants != null) return newConstants;   // null = keep; a map fully replaces
        if (!diff)                return base.constants();

        Map<String, JsonNode> result = new LinkedHashMap<>(base.constants());
        for (String name : removeConstants) {
            List<String> locations = constantReferenceLocations(base, name);
            if (!locations.isEmpty()) {
                throw new IllegalArgumentException(
                        "SpecEvolution: removeConstants cannot drop '" + name
                        + "' — $const." + name + " is still referenced at " + String.join(", ", locations)
                        + " (textual scan; dynamic $const[...] access is not detected)");
            }
            result.remove(name);
        }
        result.putAll(upsertConstants);
        return result;
    }

    /** Locations in {@code base}'s expression bodies that textually reference {@code $const.<name>}. */
    private static List<String> constantReferenceLocations(ModelSpec base, String name) {
        List<String> out = new ArrayList<>();
        int i = 0;
        for (DerivationSpec d : base.derivations()) {
            if (references(d.expr(), name)) out.add("derivations[" + i + "] (" + d.path() + ")");
            i++;
        }
        i = 0;
        for (MetaDerivationSpec md : base.metaDerivations()) {
            if (references(md.expr(), name)) out.add("metaDerivations[" + i + "]");
            i++;
        }
        i = 0;
        for (ConstraintSpec c : base.constraints()) {
            if (references(c.expr(), name)) out.add("constraints[" + i + "] (" + c.id() + ")");
            i++;
        }
        i = 0;
        for (DefaultValueSpec dv : base.defaultValues()) {
            if (references(dv.expr(), name)) out.add("defaultValues[" + i + "] (" + dv.path() + ")");
            i++;
        }
        i = 0;
        for (EffectSpec e : base.effects()) {
            if (references(serialize(e), name)) out.add("effects[" + i + "] (" + e.id() + ")");
            i++;
        }
        if (base.viewDefinition() != null && references(base.viewDefinition().toString(), name)) {
            out.add("viewDefinition");
        }
        return out;
    }

    /** True when {@code text} contains {@code $const.<name>} or {@code $const."<name>"}. */
    private static boolean references(String text, String name) {
        if (text == null || text.isEmpty()) return false;
        if (text.contains("$const.\"" + name + "\"")) return true;
        String needle = "$const." + name;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            int after = idx + needle.length();
            char c = after < text.length() ? text.charAt(after) : ' ';
            if (!(Character.isLetterOrDigit(c) || c == '_')) return true;  // identifier boundary
            idx = after;
        }
        return false;
    }

    private static String serialize(Object value) {
        try {
            return CONST_SCAN_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "";
        }
    }

    // ── View-definition evolution ────────────────────────────────────────────────

    private boolean hasViewDiff() {
        return newDefaultView != null || !upsertViews.isEmpty() || !removeViews.isEmpty()
                || !upsertComponents.isEmpty() || !removeComponents.isEmpty();
    }

    /** True when this evolution changes the view definition (wholesale or via any diff tier). */
    public boolean touchesView() {
        return newViewDefinition != null || hasViewDiff();
    }

    private JsonNode evolveView(JsonNode baseView) {
        boolean diff = hasViewDiff();
        if (newViewDefinition != null && diff) {
            throw new IllegalArgumentException(
                    "SpecEvolution: newViewDefinition cannot be combined with view diff fields "
                    + "(newDefaultView/upsertViews/removeViews/upsertComponents/removeComponents)");
        }
        if (newViewDefinition != null) return newViewDefinition;
        if (!diff)                     return baseView;
        return ViewDefinitionSplice.apply(
                baseView, upsertViews, removeViews, upsertComponents, removeComponents, newDefaultView);
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private static <T> List<T> evolveList(
            List<T> existing,
            List<T> upsert,
            List<String> remove,
            Function<T, String> keyFn) {

        Set<String> removeKeys  = Set.copyOf(remove);
        Map<String, T> upsertMap = upsert.stream()
                .collect(Collectors.toMap(keyFn, Function.identity(), (a, b) -> b));

        List<T> result = new ArrayList<>();
        for (T item : existing) {
            String key = keyFn.apply(item);
            if (removeKeys.contains(key)) continue;          // removed
            result.add(Objects.requireNonNullElse(upsertMap.remove(key), item)); // updated or kept
        }
        result.addAll(upsertMap.values()); // new additions
        return result;
    }
}
