package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Applies JSONata-syntax repairs to <em>decoded expression strings only</em>, located by a
 * registry of known expression positions in a parsed {@code ModelSpec}/{@code SpecEvolution} tree.
 *
 * <p>This is the tree-walk half of the two-stage repair split (see the LLM pipeline design):
 * JSON-structural repair ({@code repairJson}/{@code collapseStringNewlines}) runs on the raw text
 * <em>before</em> parsing; expression repair runs here, <em>after</em> parsing, applied only where a
 * JSONata expression can legitimately appear. Confining the risky rewrites to real expression
 * locations means a constraint {@code message}, a view {@code helperText}, or any other user-visible
 * string containing {@code ==}, {@code !==}, {@code mod}, or {@code $toInteger(} survives
 * byte-for-byte instead of being silently corrupted by a whole-document regex.
 *
 * <p>The single-expression transform reuses {@link SpecGenerator#rawExpressionPasses(String)}
 * verbatim by re-encoding the decoded expression as a one-string JSON document, so the well-tested
 * pass logic is unchanged — only its <em>scope</em> narrows from the whole document to one field.
 *
 * <p>Kept adjacent to the expression-location tables in {@code SpecGenerationSchema} (both describe
 * the same shape); update them together.
 */
final class ExpressionRepairer {

    private ExpressionRepairer() {}

    private static final ObjectMapper M = new ObjectMapper();

    // Array-of-objects fields whose items each carry a single JSONata "expr" (ModelSpec sections and
    // their SpecEvolution upsert twins).
    private static final List<String> EXPR_ITEM_ARRAYS = List.of(
            "derivations", "upsertDerivations",
            "metaDerivations", "upsertMetaDerivations",
            "constraints", "upsertConstraints",
            "defaultValues", "upsertDefaultValues");

    // Effect fields that are scalar JSONata expressions (payload / response.set values are handled
    // separately as maps of expressions).
    private static final List<String> EFFECT_EXPR_FIELDS = List.of(
            "trigger", "dedupeKey", "afterMs", "at", "prompt");

    // Component fields that hold a JSONata expression when they are textual (they may also be a plain
    // boolean/static string, in which case repair is skipped).
    private static final List<String> COMPONENT_EXPR_FIELDS = List.of(
            "visible", "enabled", "readOnly", "required", "className", "text", "optionsExpr");

    /**
     * Runs the full document repair: parse, and on success walk the expression registry applying
     * {@link #repair(String)} at each hit; on parse failure fall back to the whole-document
     * raw-string passes (which can sometimes regex-rescue malformed JSON). Mirrors the old
     * {@code fixExpressions} contract but only rewrites genuine expression locations.
     */
    static String fixDocument(String json) {
        try {
            JsonNode root = M.readTree(json);
            if (root instanceof ObjectNode obj) {
                repairInTree(obj);
                return M.writeValueAsString(obj);
            }
        } catch (JsonProcessingException ignored) {
            // Not parseable as a JSON object — fall through to the raw whole-document rescue.
        }
        return SpecGenerator.rawExpressionPasses(json);
    }

    /** Repairs every known expression location in {@code root}, in place. */
    static void repairInTree(ObjectNode root) {
        for (String field : EXPR_ITEM_ARRAYS) {
            for (JsonNode item : root.path(field)) {
                if (item instanceof ObjectNode obj) repairField(obj, "expr");
            }
        }
        repairEffects(root.path("effects"));
        repairEffects(root.path("upsertEffects"));

        // View expressions live in a component tree that can appear wholesale or as a diff.
        repairViewSubtree(root.path("viewDefinition"));
        repairViewSubtree(root.path("newViewDefinition"));
        repairViewSubtree(root.path("upsertViews"));
        repairViewSubtree(root.path("upsertComponents"));
    }

    private static void repairEffects(JsonNode effects) {
        if (!effects.isArray()) return;
        for (JsonNode effect : effects) {
            if (!(effect instanceof ObjectNode obj)) continue;
            for (String f : EFFECT_EXPR_FIELDS) repairField(obj, f);
            repairMapValues(obj.path("payload"));
            repairMapValues(obj.path("response").path("set"));
        }
    }

    /** Repairs every textual value of an object whose values are all JSONata expressions. */
    private static void repairMapValues(JsonNode map) {
        if (!(map instanceof ObjectNode obj)) return;
        obj.fieldNames().forEachRemaining(name -> {
            JsonNode v = obj.get(name);
            if (v != null && v.isTextual()) obj.put(name, repair(v.asText()));
        });
    }

    /**
     * Recursively repairs component-level expression fields anywhere in a view subtree
     * (views, nested groups/fieldsets, section items, event handlers).
     */
    private static void repairViewSubtree(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (String f : COMPONENT_EXPR_FIELDS) repairField(obj, f);
            if (obj.get("onChange") instanceof ObjectNode oc) repairField(oc, "mutations");
            if (obj.get("onClick")  instanceof ObjectNode oc) repairField(oc, "mutations");
            obj.forEach(ExpressionRepairer::repairViewSubtree);
        } else if (node.isArray()) {
            node.forEach(ExpressionRepairer::repairViewSubtree);
        }
    }

    private static void repairField(ObjectNode obj, String field) {
        JsonNode v = obj.get(field);
        if (v != null && v.isTextual()) obj.put(field, repair(v.asText()));
    }

    /**
     * Applies the JSONata-syntax passes to a single decoded expression string. Reuses
     * {@link SpecGenerator#rawExpressionPasses(String)} by re-encoding the value as a one-string JSON
     * document (so a JSONata {@code "…"} literal becomes the {@code \"…\"} the passes expect) and
     * decoding the result back. Returns the input unchanged on the (impossible) encoding failure.
     */
    static String repair(String expr) {
        if (expr == null || expr.isEmpty()) return expr;
        try {
            String encoded = M.writeValueAsString(expr);          // "…" JSON string form
            String fixed   = SpecGenerator.rawExpressionPasses(encoded);
            return M.readValue(fixed, String.class);
        } catch (JsonProcessingException e) {
            return expr;
        }
    }
}
