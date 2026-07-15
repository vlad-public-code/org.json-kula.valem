package org.json_kula.valem.api.reference;

import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.LineageEntry;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves a model {@code local → web} (promote / publish, references design §7.1). One-way: there is no
 * demote (§7.2) — a published coordinate is a ratchet; to iterate privately you branch it instead.
 *
 * <p>Enforces the <b>reference-locality closure</b> before publishing: every reference in the model's
 * transitive closure (link {@code target.ref}s + {@code lineage}) must already be web-resolvable, so a
 * third-party resolver can satisfy the promoted artifact. A local-only dependency blocks promotion —
 * promote bottom-up (leaves first).
 */
@Component
public class ModelPromoter {

    private final ModelService service;
    private final ModelResolver resolver;

    public ModelPromoter(ModelService service, ModelResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    /** Promote {@code modelId} into the web repository {@code targetRepoId}. */
    public void promote(String modelId, String targetRepoId) {
        ModelSpec spec = service.getSpec(modelId);

        ModelRepository target = resolver.repository(targetRepoId).orElseThrow(() ->
                new ReferenceException.PromotionClosureFailure(
                        "unknown target repository '" + targetRepoId + "'"));
        if (target.isLocalClass()) {
            throw new ReferenceException.DemoteUnsupported(
                    "cannot promote into the local repository '" + targetRepoId
                    + "' — web→local is not supported; branch instead");
        }
        if (!target.canPublish()) {
            throw new ReferenceException.PromotionClosureFailure(
                    "repository '" + targetRepoId + "' does not accept published models");
        }

        List<String> offending = new ArrayList<>();
        for (String ref : closureRefs(spec)) {
            if (!ModelCoordinate.isValid(ref)) continue;
            if (!resolver.isWebResolvable(ModelCoordinate.parse(ref))) offending.add(ref);
        }
        if (!offending.isEmpty()) {
            throw new ReferenceException.PromotionClosureFailure(
                    "promote of '" + modelId + "' blocked — these references are not web-resolvable "
                    + "(promote them first): " + offending);
        }

        target.publish(spec);
    }

    /** Every coordinate the model transitively depends on: its link targets and its lineage ancestors. */
    private static List<String> closureRefs(ModelSpec spec) {
        List<String> refs = new ArrayList<>();
        for (EffectSpec e : spec.effects()) {
            if (e.target() != null && e.target().ref() != null && !e.target().ref().isBlank()) {
                refs.add(e.target().ref());
            }
        }
        for (LineageEntry entry : spec.lineage()) {
            if (entry.ref() != null) refs.add(entry.ref());
        }
        return refs;
    }
}
