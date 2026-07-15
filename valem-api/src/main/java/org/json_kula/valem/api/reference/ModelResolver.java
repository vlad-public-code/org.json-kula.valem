package org.json_kula.valem.api.reference;

import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.util.SemVer;

import java.util.List;
import java.util.Optional;

/**
 * Turns a location-independent {@link ModelCoordinate} into something callable, by walking a
 * priority-ordered {@link ModelRepository} chain (references design §4). The first repository holding
 * a satisfying version wins — repository priority dominates version selection; within the winning
 * repository the highest satisfying version is chosen.
 *
 * <p>Range resolution lives here (not in the repos): for a {@code name@range} the resolver asks each
 * repo for {@link ModelRepository#listVersions} in priority order and pins the highest satisfying
 * version, then does an exact lookup. M2 wires a single {@link LocalModelRepository}; the chain shape
 * is already correct for adding remote repos in M6.
 */
public class ModelResolver {

    private final List<ModelRepository> repositories;

    public ModelResolver(List<ModelRepository> repositories) {
        this.repositories = List.copyOf(repositories);
    }

    public List<ModelRepository> repositories() {
        return repositories;
    }

    /** Resolve a runnable link handle, or empty if no repository holds a satisfying running model. */
    public Optional<ModelLink> resolveLink(ModelCoordinate coord) {
        ModelCoordinate exact = pin(coord);
        for (ModelRepository repo : repositories) {
            Optional<ModelLink> link = repo.resolveLink(exact);
            if (link.isPresent()) return link;
        }
        return Optional.empty();
    }

    /** Resolve a spec (for branch/template materialization or browse), pinned to the exact version+digest. */
    public Optional<ResolvedSpec> resolveSpec(ModelCoordinate coord) {
        ModelCoordinate exact = pin(coord);
        for (ModelRepository repo : repositories) {
            Optional<ResolvedSpec> spec = repo.resolveSpec(exact);
            if (spec.isPresent()) return spec;
        }
        return Optional.empty();
    }

    /**
     * Whether {@code coord} resolves from a <b>web-class</b> repository — the reachability that the
     * reference-locality rule and the promote closure check require (references §6/§7.1). A coordinate
     * that only resolves in the local repository is <em>not</em> web-resolvable.
     */
    public boolean isWebResolvable(ModelCoordinate coord) {
        ModelCoordinate exact = pin(coord);
        for (ModelRepository repo : repositories) {
            if (!repo.isLocalClass() && repo.resolveSpec(exact).isPresent()) return true;
        }
        return false;
    }

    /** The configured repository with the given id, if present. */
    public Optional<ModelRepository> repository(String id) {
        return repositories.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /**
     * Resolves a range to an exact coordinate by scanning repos in priority order for the highest
     * satisfying version. Exact / digest / unversioned coordinates pass through unchanged.
     */
    private ModelCoordinate pin(ModelCoordinate coord) {
        if (coord.version() instanceof ModelCoordinate.Range) {
            for (ModelRepository repo : repositories) {
                SemVer best = null;
                for (SemVer v : repo.listVersions(coord)) {
                    if (coord.satisfiedBy(v) && (best == null || v.compareTo(best) > 0)) {
                        best = v;
                    }
                }
                if (best != null) return coord.withExactVersion(best);
            }
        }
        return coord;
    }
}
