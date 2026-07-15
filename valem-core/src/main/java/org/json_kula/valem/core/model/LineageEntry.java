package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One pinned ancestor in a materialized branch's {@code lineage} (references design §5.2) — provenance
 * for audit and the lock that makes re-materialization reproducible. Read-only: written by the
 * {@code TemplateMaterializer}, never authored.
 *
 * <p>{@code owner} records the ancestor's owning org — this is what lets the materializer decide
 * whether an inherited effect crossed an ownership boundary and must be approved before it may run
 * (inherited-effect approval, multi-tenant-authorization §4.2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LineageEntry(
        String ref,        // the ancestor coordinate identity ([namespace/]name)
        String version,    // resolved exact semver
        String digest,     // sha256: content digest of the served ancestor spec
        String repo,       // repository id the ancestor resolved from
        String owner       // ancestor's owning org (feeds inherited-effect approval)
) {
    @JsonCreator
    public static LineageEntry of(
            @JsonProperty("ref")     String ref,
            @JsonProperty("version") String version,
            @JsonProperty("digest")  String digest,
            @JsonProperty("repo")    String repo,
            @JsonProperty("owner")   String owner) {
        return new LineageEntry(ref, version, digest, repo, owner);
    }
}
