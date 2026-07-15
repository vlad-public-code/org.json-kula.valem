package org.json_kula.valem.api.reference;

import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.util.CanonicalJson;
import org.json_kula.valem.core.util.SemVer;
import org.json_kula.valem.service.ModelNotFoundException;
import org.json_kula.valem.service.ModelService;

import java.util.List;
import java.util.Optional;

/**
 * The {@code local} transport (references design §4.1): resolves a coordinate against the in-process
 * running models held by {@link ModelService}. The runtime registry is keyed by {@code id}
 * (the coordinate's version-less identity), so at most one version of a name runs here — this repo
 * matches a coordinate iff a running model's id equals the coordinate's identity and its version
 * satisfies the version-spec. It doubles as the resolution cache for the wider chain.
 */
public class LocalModelRepository implements ModelRepository {

    private final ModelService service;

    public LocalModelRepository(ModelService service) {
        this.service = service;
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public RepositoryClass repositoryClass() {
        return RepositoryClass.LOCAL;   // the in-process registry is inherently private + the cache
    }

    /** The running model's spec, if one is registered under the coordinate's identity. */
    private Optional<ModelSpec> runningSpec(ModelCoordinate coord) {
        try {
            return Optional.of(service.getSpec(coord.identity()));
        } catch (ModelNotFoundException e) {
            return Optional.empty();
        }
    }

    private static boolean versionMatches(ModelCoordinate coord, ModelSpec spec) {
        if (!SemVer.isValid(spec.version())) return false;
        SemVer running = SemVer.parse(spec.version());
        // A digest pin is verified in resolveSpec, not here; treat it as "match the running version".
        return switch (coord.version()) {
            case ModelCoordinate.Digest d -> true;
            default -> coord.satisfiedBy(running);
        };
    }

    @Override
    public List<SemVer> listVersions(ModelCoordinate nameOnly) {
        return runningSpec(nameOnly)
                .filter(s -> SemVer.isValid(s.version()))
                .map(s -> List.of(SemVer.parse(s.version())))
                .orElse(List.of());
    }

    @Override
    public Optional<ResolvedSpec> resolveSpec(ModelCoordinate coord) {
        return runningSpec(coord)
                .filter(spec -> versionMatches(coord, spec))
                .map(spec -> {
                    SemVer v = SemVer.parse(spec.version());
                    String digest = CanonicalJson.prefixedDigest(spec);
                    if (coord.version() instanceof ModelCoordinate.Digest pinned
                            && !pinned.sha256().equals(digest)) {
                        return null;   // digest mismatch — this repo does not serve it
                    }
                    return new ResolvedSpec(spec, coord.withExactVersion(v), digest, id());
                });
    }

    @Override
    public Optional<ModelLink> resolveLink(ModelCoordinate coord) {
        return runningSpec(coord)
                .filter(spec -> versionMatches(coord, spec))
                .map(spec -> new LocalModelLink(service, coord.identity(), coord));
    }
}
