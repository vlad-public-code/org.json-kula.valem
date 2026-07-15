package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetaDerivationSpec(
        String path,
        MetaProperty property,
        String expr,
        String description
) {
    @JsonCreator
    public static MetaDerivationSpec of(
            @JsonProperty(value = "path",     required = true) String path,
            @JsonProperty(value = "property", required = true) MetaProperty property,
            @JsonDeserialize(using = AnyToStringDeserializer.class)
            @JsonProperty(value = "expr",     required = true) String expr,
            @JsonProperty("description")                        String description
    ) {
        return new MetaDerivationSpec(path, property, expr, description);
    }

    /** Node key used in the dependency graph: "path#property". */
    public String nodeKey() {
        return path + "#" + property.name().toLowerCase();
    }

    /**
     * Accepts any JSON token as a string — LLMs sometimes emit number or array literals
     * (e.g. {@code "expr": 100} or {@code "expr": [false, true]}) for meta-derivation
     * expressions that the runtime expects to be JSONata expression strings.
     */
    static final class AnyToStringDeserializer extends StdDeserializer<String> {
        AnyToStringDeserializer() { super(String.class); }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonNode node = ctx.readTree(p);
            return node.isTextual() ? node.asText() : node.toString();
        }
    }
}

