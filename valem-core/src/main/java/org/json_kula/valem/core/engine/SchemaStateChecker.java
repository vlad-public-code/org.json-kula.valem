package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.graph.SchemaPaths;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks an existing document against a (possibly changed) JSON Schema, so a spec evolution
 * can be rejected up-front when it would strand live state — e.g. retyping a field from
 * {@code number} to {@code string} while the document still holds numbers.
 *
 * <p>Reuses the same keyword subset as mutation-time validation ({@link SchemaValidator}),
 * but <b>ignores {@code readOnly}</b>: that keyword gates <em>writes</em>, whereas existing
 * values in read-only / derived fields are legitimate and must not be flagged.
 */
public final class SchemaStateChecker {

    /** A single incompatibility: the state at {@code path} violates the new schema. */
    public record Incompatibility(String path, String message) {}

    private SchemaStateChecker() {}

    /**
     * Walks every node of {@code document} and validates it against the sub-schema the new
     * {@code schema} assigns to its path (resolving local {@code $ref}s). Returns all
     * incompatibilities (empty means the state conforms).
     */
    public static List<Incompatibility> check(JsonNode schema, JsonNode document) {
        List<Incompatibility> out = new ArrayList<>();
        if (schema == null || document == null) return out;
        walk(schema, "$", document, out);
        return out;
    }

    private static void walk(JsonNode schema, String path, JsonNode value, List<Incompatibility> out) {
        ObjectNode effective = SchemaPaths.resolve(schema, path);
        if (effective != null && !effective.isEmpty()) {
            ObjectNode forCheck = effective;
            if (effective.has("readOnly")) {
                forCheck = effective.deepCopy();
                forCheck.remove("readOnly");
            }
            for (SchemaViolationException.Violation v : SchemaValidator.validate(forCheck, path, value)) {
                if (!"readOnly".equals(v.keyword())) {
                    out.add(new Incompatibility(path, v.message()));
                }
            }
        }

        if (value.isObject()) {
            value.fields().forEachRemaining(e -> walk(schema, path + "." + e.getKey(), e.getValue(), out));
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                walk(schema, path + "[" + i + "]", value.get(i), out);
            }
        }
    }
}
