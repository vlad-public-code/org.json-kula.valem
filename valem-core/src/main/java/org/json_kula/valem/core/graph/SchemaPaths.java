package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.state.PathConverter;

/**
 * Shared navigation over a JSON Schema document by <em>canonical data path</em> (DEC-6),
 * resolving local definition references ({@code {"$ref": "#/$defs/<Name>"}}) as it goes.
 *
 * <p>Navigation descends object properties via {@code properties} and array elements via
 * {@code items} (Draft 2020-12 single-schema style). Both a concrete index ({@code items[0]})
 * and the wildcard ({@code items[*]}) navigate through {@code items} identically.
 *
 * <p>Only <b>local</b> refs are understood — {@code #/$defs/<Name>} pointing at a top-level
 * {@code $defs} entry. Non-local ref forms are left unresolved here; {@link ModelSpecValidator}
 * rejects them separately, so this class never has to reason about external documents.
 *
 * <p>A {@link #MAX_REF_DEPTH ref-chain depth cap} guards against {@code $ref → $ref} loops;
 * exceeding it resolves to the empty schema, exactly like an absent path.
 */
public final class SchemaPaths {

    /** Maximum {@code $ref → $ref} hops before a chain is treated as absent (loop guard). */
    public static final int MAX_REF_DEPTH = 32;

    private static final String REF_PREFIX = "#/$defs/";

    private SchemaPaths() {}

    /**
     * Returns the sub-schema for {@code fieldPath}, resolving every {@code $ref} crossed
     * (including one on the terminal node). Returns a fresh empty object when the path is
     * absent from the schema, so callers can safely mutate the result.
     *
     * @param rootSchema the model's full JSON Schema document (carries {@code $defs})
     * @param fieldPath  a canonical JsonPath address, e.g. {@code "$.order.items[0].qty"}
     */
    public static ObjectNode resolve(JsonNode rootSchema, String fieldPath) {
        if (rootSchema == null || rootSchema.isMissingNode()) return emptyObject();

        JsonNode cursor = deref(rootSchema, rootSchema);
        for (String seg : PathConverter.toSegments(fieldPath)) {
            if (cursor == null || cursor.isMissingNode() || !cursor.isObject()) return emptyObject();
            cursor = isArraySegment(seg)
                    ? cursor.path("items")
                    : cursor.path("properties").path(seg);
            cursor = deref(rootSchema, cursor);
        }

        if (cursor == null || !cursor.isObject()) return emptyObject();
        return cursor.deepCopy();
    }

    /**
     * Resolves a chain of local {@code $ref}s starting at {@code node}, returning the first
     * node that is not a local ref (or the last reachable node when the chain dangles or loops).
     * A non-ref node is returned unchanged.
     */
    public static JsonNode deref(JsonNode rootSchema, JsonNode node) {
        int hops = 0;
        while (node != null && node.isObject() && node.has("$ref")) {
            if (hops++ >= MAX_REF_DEPTH) return MissingNodeHolder.MISSING;
            String name = localDefName(node.get("$ref"));
            if (name == null) return node;  // non-local ref — leave for the validator to reject
            node = rootSchema.path("$defs").path(name);
        }
        return node;
    }

    /**
     * Returns the definition name of a local {@code $ref} value ({@code "#/$defs/Money"} →
     * {@code "Money"}), or {@code null} when the value is not a local {@code #/$defs/} ref.
     */
    public static String localDefName(JsonNode refValue) {
        if (refValue == null || !refValue.isTextual()) return null;
        String ref = refValue.asText();
        if (!ref.startsWith(REF_PREFIX)) return null;
        String name = ref.substring(REF_PREFIX.length());
        return name.isEmpty() || name.indexOf('/') >= 0 ? null : name;
    }

    /** True when a data-path segment denotes an array element ({@code "0"}, {@code "12"} or {@code "[*]"}). */
    public static boolean isArraySegment(String seg) {
        if ("[*]".equals(seg)) return true;
        if (seg == null || seg.isEmpty()) return false;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static ObjectNode emptyObject() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static final class MissingNodeHolder {
        static final JsonNode MISSING = JsonNodeFactory.instance.objectNode().path("__absent__");
    }
}
