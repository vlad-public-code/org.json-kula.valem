package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.model.MetaProperty;
import org.json_kula.valem.core.state.ModelState;

/**
 * Builds the <em>effective schema</em> for a single field path by overlaying
 * live meta-derivation results onto the field's static JSON Schema fragment.
 *
 * <p>The effective schema is the JSON Schema subtree for the field with any
 * meta-derivation-computed properties (minimum, maximum, multipleOf, required, pattern, enum,
 * readOnly, relevant) merged on top. Since meta values are data-dependent, siblings
 * in an array can have different effective schemas.
 *
 * <p>Returned nodes are new {@link ObjectNode} instances — safe to cache or pass
 * to validators without risking mutation of the original spec schema.
 */
public final class EffectiveSchemaBuilder {

    private EffectiveSchemaBuilder() {}

    /**
     * Returns the effective schema for {@code fieldPath} (a concrete JsonPath expression,
     * no wildcards). Returns an empty object node when the path is absent from the static schema.
     *
     * @param model     compiled model carrying the static JSON Schema
     * @param state     current model state carrying the meta cache
     * @param fieldPath concrete JsonPath expression, e.g. {@code "$.order.items[0].qty"}
     */
    public static ObjectNode build(CompiledModel model, ModelState state, String fieldPath) {
        // 1. Locate the static schema fragment for this field (memoized per model, returned as a
        //    fresh deep copy so the meta overlay below is safe — audit CPU-10).
        ObjectNode effective = model.staticSchema(fieldPath);

        // 2. Overlay every meta-property that has a live computed value
        for (MetaProperty prop : MetaProperty.values()) {
            String nodeKey = fieldPath + "#" + prop.name().toLowerCase();
            JsonNode metaValue = state.getMeta(nodeKey);
            if (metaValue == null || metaValue.isNull() || metaValue.isMissingNode()) continue;

            String keyword = prop.jsonSchemaKeyword();
            if (keyword != null) {
                effective.set(keyword, metaValue);
            } else if (prop == MetaProperty.RELEVANT) {
                // "relevant" is a Valem extension — not a JSON Schema keyword
                effective.set("x-valem-relevant", metaValue);
            }
        }

        return effective;
    }
}
