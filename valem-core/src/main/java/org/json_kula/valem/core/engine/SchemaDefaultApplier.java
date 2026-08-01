package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.state.ModelState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds a freshly-created model with the {@code default} values declared in its JSON Schema.
 *
 * <p>Standard JSON Schema {@code default} keywords (e.g. {@code {"type":"number","default":0.22}})
 * are applied at model creation with <b>fill-absent</b> semantics: a field is seeded only if nothing
 * else already set it. This runs alongside {@link DefaultValueApplier} — the expression-based
 * {@code defaultValues} rules — but those take precedence (they run first; schema defaults never
 * overwrite a value already present). Read-only derived fields are never seeded.
 *
 * <p>Only object {@code properties} are walked (recursively into nested objects). A property that
 * declares its own {@code default} is seeded wholesale and not descended into; array item schemas
 * are not descended (an array's {@code default} is the whole array value, applied as-is).
 */
public final class SchemaDefaultApplier {

    private SchemaDefaultApplier() {}

    /**
     * Seeds schema {@code default} values into {@code state} for any path still absent, and returns
     * the concrete paths written (in document order).
     */
    public static List<String> apply(CompiledModel model, ModelState state) {
        JsonNode schema = model.spec().schema();
        if (schema == null || !schema.isObject()) return List.of();

        Map<String, JsonNode> defaults = new LinkedHashMap<>();
        collect(schema, "$", defaults);
        if (defaults.isEmpty()) return List.of();

        List<String> written = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : defaults.entrySet()) {
            String path = e.getKey();
            // Never overwrite an already-present value (a defaultValues rule or a prior write), and
            // never write a derived (read-only) field.
            if (state.existsInBase(path)) continue;
            if (model.derivationFor(path) != null) continue;
            // Deep-copy the default: the source node is owned by the (shared, per-spec) schema, so
            // seeding it by reference would let a later in-place mutation of an array/object default
            // (e.g. board[0] = "X") corrupt the shared schema — leaking state into every other model
            // created from the same spec. Each model must get its own copy.
            state.setValue(path, e.getValue().deepCopy());
            written.add(path);
        }
        return written;
    }

    /**
     * Walks {@code schema.properties} collecting (path → default value). A property carrying its own
     * {@code default} is recorded and not recursed into; otherwise nested objects are descended.
     */
    private static void collect(JsonNode schema, String prefix, Map<String, JsonNode> out) {
        JsonNode props = schema.get("properties");
        if (props == null || !props.isObject()) return;
        props.fields().forEachRemaining(entry -> {
            String path = prefix + "." + entry.getKey();
            JsonNode propSchema = entry.getValue();
            if (propSchema == null || !propSchema.isObject()) return;

            JsonNode def = propSchema.get("default");
            if (def != null && !def.isNull()) {
                out.put(path, def);
            } else if (propSchema.has("properties")) {
                collect(propSchema, path, out);
            }
        });
    }
}
