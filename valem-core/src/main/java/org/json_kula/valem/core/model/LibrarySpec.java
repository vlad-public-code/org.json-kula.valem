package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model's JSONata library: named functions and derived values usable from <b>every</b>
 * expression in the spec as {@code $name(...)} / {@code $name}.
 *
 * <p>Where {@code constants} shares values (bound as {@code $const}), a library shares computation,
 * through the same binding seam. A library function computes <b>only from its arguments</b> and
 * {@code $const}: it cannot read the model document, which is what keeps a library call free of
 * dependency-graph consequences — every document value it works on must be passed in at the call
 * site, where {@code ExpressionPathExtractor} sees it and records an edge.
 *
 * <h2>Authored forms</h2>
 * A bare string is the shorthand for a single definition:
 * <pre>{@code
 * "library": "( $money := function($n){ $round($n, 2) }; [\"money\"] )"
 * }</pre>
 * The object form adds signature overrides and prose:
 * <pre>{@code
 * "library": {
 *   "description": "Money rounding shared by every derivation.",
 *   "define": "( $money := function($n){ $round($n, 2) }; [\"money\"] )",
 *   "signatures": { "money": "<n:n>" }
 * }
 * }</pre>
 *
 * <h2>Layers</h2>
 * Internally a library is an <b>ordered list of layers</b>. A model authored by one owner has
 * exactly one; a branch that inherits a template's library, or that names {@code extends}, gains
 * leading layers which are resolved and inlined at materialization time. Layers compile and bind in
 * order and a later layer wins a name collision, so a branch may both call and override an
 * inherited export. The list shape is the storage form from the outset because retrofitting it onto
 * a scalar field would be a migration of persisted specs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LibrarySpec(
        List<LibraryLayer> layers,
        List<String> extendsRefs,   // authored coordinates; emptied once resolved into leading layers
        String description
) {
    /**
     * Accepts the bare-string shorthand, the authored object form ({@code define}/{@code signatures}
     * collapse into a single trailing layer), and the materialized {@code layers} form.
     *
     * <p>A delegating creator rather than a property-based one, because the three shapes are not
     * distinguishable by property names alone — a bare string has none.
     */
    @JsonCreator
    public static LibrarySpec from(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;

        if (node.isTextual()) {
            return new LibrarySpec(List.of(LibraryLayer.own(node.asText(), null)), List.of(), null);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(
                    "library must be a definition string or an object with \"define\" or \"layers\"");
        }

        String description = node.hasNonNull("description") ? node.get("description").asText() : null;

        List<String> extendsRefs = new ArrayList<>();
        JsonNode ext = node.get("extends");
        if (ext != null && ext.isArray()) {
            ext.forEach(e -> { if (e.isTextual()) extendsRefs.add(e.asText()); });
        } else if (ext != null && ext.isTextual()) {
            extendsRefs.add(ext.asText());          // a single coordinate needs no array
        }

        List<LibraryLayer> layers = new ArrayList<>();
        JsonNode declared = node.get("layers");
        if (declared != null && declared.isArray()) {
            for (JsonNode l : declared) layers.add(readLayer(l));
        }
        // "define" is the model's OWN layer and therefore always last, whether or not "layers" was
        // given: a materialized spec that also carries an own definition keeps it after its ancestors.
        if (node.hasNonNull("define")) {
            layers.add(LibraryLayer.own(node.get("define").asText(), readSignatures(node.get("signatures"))));
        }
        if (layers.isEmpty() && extendsRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "library requires \"define\" (a JSONata definition expression), \"layers\", or \"extends\"");
        }
        return new LibrarySpec(List.copyOf(layers), List.copyOf(extendsRefs), description);
    }

    private static LibraryLayer readLayer(JsonNode l) {
        if (l.isTextual()) return LibraryLayer.own(l.asText(), null);
        return LibraryLayer.of(
                l.path("define").asText(null),
                readSignatures(l.get("signatures")),
                readStrings(l.get("requiresConstants")),
                l.path("ref").asText(null),
                l.path("version").asText(null),
                l.path("digest").asText(null),
                l.path("repo").asText(null),
                l.path("owner").asText(null));
    }

    private static Map<String, String> readSignatures(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static List<String> readStrings(JsonNode node) {
        if (node == null || !node.isArray()) return null;
        List<String> out = new ArrayList<>();
        node.forEach(n -> { if (n.isTextual()) out.add(n.asText()); });
        return out;
    }

    /** Serializes back to the canonical object form so a spec round-trips unchanged. */
    @JsonValue
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (description != null)     out.put("description", description);
        if (!extendsRefs.isEmpty())  out.put("extends", extendsRefs);
        // A single own layer renders as plain "define"/"signatures" — the shape an author wrote, and
        // the shape every existing example and doc shows. Only an inherited chain renders as "layers".
        if (layers.size() == 1 && !layers.getFirst().isInherited()) {
            LibraryLayer only = layers.getFirst();
            out.put("define", only.define());
            if (!only.signatures().isEmpty())        out.put("signatures", only.signatures());
            if (!only.requiresConstants().isEmpty()) out.put("requiresConstants", only.requiresConstants());
        } else if (!layers.isEmpty()) {
            out.put("layers", layers);
        }
        return out;
    }

    /** This model's own layer — the only one an evolution may replace — or {@code null} if none. */
    public LibraryLayer ownLayer() {
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (!layers.get(i).isInherited()) return layers.get(i);
        }
        return null;
    }

    /** A copy of this spec whose own layer is {@code layer} ({@code null} drops it). */
    public LibrarySpec withOwnLayer(LibraryLayer layer) {
        List<LibraryLayer> kept = new ArrayList<>(layers.stream().filter(LibraryLayer::isInherited).toList());
        if (layer != null) kept.add(layer);
        return kept.isEmpty() && extendsRefs.isEmpty()
                ? null
                : new LibrarySpec(List.copyOf(kept), extendsRefs, description);
    }

    /** A single-layer library from a definition expression — the common case, for tests and callers. */
    public static LibrarySpec ofDefinition(String define) {
        return new LibrarySpec(List.of(LibraryLayer.own(define, null)), List.of(), null);
    }
}
