package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One JSONata definition layer of a model's {@link LibrarySpec}.
 *
 * <p>{@code define} is a plain JSONata expression that binds names and returns the names to export
 * as its last value. It is compiled once via {@code JsonataExpressionFactory.compileLibrary}; its
 * exports are then bound in every expression the model evaluates.
 *
 * <p>A model authored by one owner has exactly one layer, whose provenance fields are all
 * {@code null}. Further layers arrive from a template ancestor or from {@code LibrarySpec.extends};
 * those carry the same five pinned fields a {@link LineageEntry} does, written by the materializer.
 *
 * @param define            the definition expression
 * @param signatures        optional per-export signature overrides ({@code "<n:n>"}), keyed without
 *                          the leading {@code $}; tightens argument validation and coercion
 * @param requiresConstants constants this layer reads through {@code $const}. Declaring them turns
 *                          an inherited layer's dependency on the consuming model into a checked
 *                          contract; ignored for a model's own layer
 * @param ref               resolved ancestor coordinate identity, or {@code null} for an own layer
 * @param version           resolved exact semver of the ancestor
 * @param digest            {@code sha256:} content digest of the served ancestor spec
 * @param repo              repository id the ancestor resolved from
 * @param owner             ancestor's owning org
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LibraryLayer(
        String define,
        Map<String, String> signatures,
        List<String> requiresConstants,
        String ref,
        String version,
        String digest,
        String repo,
        String owner
) {
    @JsonCreator
    public static LibraryLayer of(
            @JsonProperty(value = "define", required = true) String define,
            @JsonProperty("signatures")                      Map<String, String> signatures,
            @JsonProperty("requiresConstants")               List<String> requiresConstants,
            @JsonProperty("ref")                             String ref,
            @JsonProperty("version")                         String version,
            @JsonProperty("digest")                          String digest,
            @JsonProperty("repo")                            String repo,
            @JsonProperty("owner")                           String owner) {
        return new LibraryLayer(
                define,
                signatures        != null ? new LinkedHashMap<>(signatures)  : Map.of(),
                requiresConstants != null ? List.copyOf(requiresConstants)   : List.of(),
                ref, version, digest, repo, owner);
    }

    /** A model's own (unpinned) layer: a definition plus optional signature overrides. */
    public static LibraryLayer own(String define, Map<String, String> signatures) {
        return of(define, signatures, null, null, null, null, null, null);
    }

    /** True when this layer was resolved from another model rather than authored here. */
    public boolean isInherited() {
        return ref != null && !ref.isBlank();
    }

    /**
     * How this layer is named on the wire and in {@code origin} fields: the pinned coordinate for an
     * inherited layer, {@code "local"} for the model's own.
     */
    public String origin() {
        if (!isInherited()) return "local";
        return version != null && !version.isBlank() ? ref + "@" + version : ref;
    }
}
