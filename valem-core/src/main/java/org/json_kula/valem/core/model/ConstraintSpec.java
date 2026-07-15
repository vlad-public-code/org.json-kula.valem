package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A constraint rule. {@code path} is optional:
 * - absent â†’ global constraint evaluated against full state
 * - single string â†’ scalar-scoped constraint
 * - array of strings â†’ multi-target constraint (same expr applied to each path)
 * - string with [*] â†’ per-element array constraint
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConstraintSpec(
        String id,
        @JsonDeserialize(using = PathListDeserializer.class)
        List<String> path,          // null = global; one entry = scalar/array; multiple = multi-target
        String expr,
        String message,
        ConstraintPolicy policy
) {

    @JsonIgnore public boolean isGlobal()      { return path == null || path.isEmpty(); }
    @JsonIgnore public boolean isArrayScoped() { return !isGlobal() && path.size() == 1 && path.getFirst().contains("[*]"); }
    @JsonIgnore public boolean isMultiTarget() { return !isGlobal() && path.size() > 1; }
    @JsonIgnore public boolean isScalar()      { return !isGlobal() && path.size() == 1 && !path.getFirst().contains("[*]"); }

    public static class PathListDeserializer extends StdDeserializer<List<String>> {
        public PathListDeserializer() { super(List.class); }

        @Override
        public List<String> deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node.isNull() || node.isMissingNode()) return null;
            if (node.isTextual()) return List.of(node.asText());
            if (node.isArray()) {
                List<String> paths = new ArrayList<>(node.size());
                node.forEach(el -> paths.add(el.asText()));
                return List.copyOf(paths);
            }
            throw new IOException("constraint 'path' must be a string or array of strings, got: " + node.getNodeType());
        }
    }
}

