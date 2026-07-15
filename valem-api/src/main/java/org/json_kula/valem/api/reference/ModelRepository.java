package org.json_kula.valem.api.reference;

import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.util.SemVer;

import java.util.List;
import java.util.Optional;

/**
 * A source of models, addressable by coordinate — one SPI, several transports (references design
 * §4.1). {@link ModelResolver} walks a priority-ordered list of these and returns the first repository
 * holding a satisfying version.
 *
 * <p>Range resolution is the resolver's job (via {@link #listVersions}); a repository answers only
 * exact lookups plus a version index. M2 ships {@link LocalModelRepository} (the {@code local}
 * transport, which doubles as the resolution cache); {@code mcp}/{@code http}/{@code filesystem}
 * arrive in later milestones.
 */
public interface ModelRepository {

    /** Stable repository id (matches the configured chain entry). */
    String id();

    /**
     * Repository {@link RepositoryClass} — {@code local} (private, process-scoped, also the cache) vs
     * {@code web} (shared, addressable-by-others). Orthogonal to the transport; drives reference
     * locality (§6) and mobility (§7).
     */
    RepositoryClass repositoryClass();

    /** Convenience: whether this repository is {@link RepositoryClass#LOCAL}. */
    default boolean isLocalClass() {
        return repositoryClass() == RepositoryClass.LOCAL;
    }

    /** All available versions of a name (empty if unknown here) — drives range resolution. MUST ignore the coordinate's version-spec. */
    List<SemVer> listVersions(ModelCoordinate nameOnly);

    /** Locate a spec by an <em>exact</em> coordinate ({@code name@exact} or {@code name@sha256:…}); empty if absent. */
    Optional<ResolvedSpec> resolveSpec(ModelCoordinate exactCoord);

    /** Obtain a callable handle to a <em>running</em> model satisfying {@code coord}; empty if none here. */
    Optional<ModelLink> resolveLink(ModelCoordinate coord);

    /** Whether a materialized model may be published (promoted) here. */
    default boolean canPublish() { return false; }

    /** Publish a materialized (self-contained) spec to this repository. */
    default void publish(ModelSpec materialized) {
        throw new UnsupportedOperationException("repository '" + id() + "' is not publishable");
    }
}
