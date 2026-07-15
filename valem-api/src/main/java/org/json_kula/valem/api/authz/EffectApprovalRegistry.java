package org.json_kula.valem.api.authz;

import org.json_kula.valem.core.engine.spi.EffectKindRegistry;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.util.CanonicalJson;
import org.json_kula.valem.service.ModelService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inherited-effect approval (multi-tenant-authorization §4.2): an effect inherited by branching a
 * <em>different owner's</em> template carries outbound I/O authority the brancher never authored, so it
 * is <b>quarantined — inert until approved</b>. This registry is the decision + consent store the effect
 * executor consults before dispatching.
 *
 * <p>An effect is quarantined iff: its executor is not {@code caller} (pure effects need no approval),
 * it carries an {@code origin} (i.e. it was inherited, not branch-authored), and {@code origin.fromOwner}
 * differs from the model's owner. Approval is keyed to the effect's {@code definitionHash} (its canonical
 * executable bytes), so a re-materialize that changes the effect re-quarantines it. Same-owner /
 * branch-authored effects always run.
 */
public class EffectApprovalRegistry {

    /** Deployment policy for inherited cross-owner effects. */
    public enum Mode { APPROVE, ALLOW, DENY }

    private final Mode mode;
    private final ModelService service;

    // modelId -> set of approved definitionHashes.
    private final Map<String, Set<String>> approved = new ConcurrentHashMap<>();

    public EffectApprovalRegistry(Mode mode, ModelService service) {
        this.mode = mode;
        this.service = service;
    }

    /** A quarantined effect awaiting the owner's approval. */
    public record PendingEffect(String effectId, String executor, String definitionHash,
                                String fromRef, String fromOwner) {}

    /**
     * Whether {@code effectId} on {@code modelId} must NOT dispatch right now (inherited cross-owner and
     * not yet approved). Computed live from the model's current spec, so it survives reload.
     */
    public boolean isBlocked(String modelId, String effectId) {
        if (mode == Mode.ALLOW) return false;
        EffectSpec e = findEffect(modelId, effectId);
        if (e == null || !requiresApproval(e, modelId)) return false;
        if (mode == Mode.DENY) return true;   // never runnable (materialize-time strip is the stronger form)
        return !approvedHashes(modelId).contains(definitionHash(e));
    }

    /** The effects on {@code modelId} currently quarantined (inherited cross-owner, unapproved). */
    public List<PendingEffect> pending(String modelId) {
        List<PendingEffect> out = new ArrayList<>();
        for (EffectSpec e : specEffects(modelId)) {
            if (isBlocked(modelId, e.id()) && e.origin() != null) {
                out.add(new PendingEffect(e.id(), e.executor(), definitionHash(e),
                        e.origin().fromRef(), e.origin().fromOwner()));
            }
        }
        return out;
    }

    /** Approve {@code effectId} on {@code modelId} — records its current definitionHash. */
    public void approve(String modelId, String effectId) {
        EffectSpec e = findEffect(modelId, effectId);
        if (e == null) throw new IllegalArgumentException("no effect '" + effectId + "' on '" + modelId + "'");
        approved.computeIfAbsent(modelId, k -> ConcurrentHashMap.newKeySet()).add(definitionHash(e));
    }

    /** Revoke a prior approval — the effect re-inerts on its next fire. */
    public void revoke(String modelId, String effectId) {
        EffectSpec e = findEffect(modelId, effectId);
        if (e != null) approvedHashes(modelId).remove(definitionHash(e));
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /** An effect needs approval when it is a non-pure effect inherited across an ownership boundary. */
    private boolean requiresApproval(EffectSpec e, String modelId) {
        if (EffectKindRegistry.CALLER.equals(e.executor())) return false;   // pure, in-core — no egress
        EffectSpec.Origin origin = e.origin();
        if (origin == null) return false;                    // branch-authored — the brancher owns it
        return !Objects.equals(origin.fromOwner(), ownerOf(modelId));  // cross-owner inheritance
    }

    /** The definition hash over an effect's executable bytes (origin excluded — it is provenance metadata). */
    public static String definitionHash(EffectSpec e) {
        return CanonicalJson.prefixedDigest(e.withOrigin(null));
    }

    /** A model's owner = its coordinate namespace (the tenant boundary); null for a namespace-less id. */
    private static String ownerOf(String modelId) {
        return ModelCoordinate.isValid(modelId) ? ModelCoordinate.parse(modelId).namespace() : null;
    }

    private Set<String> approvedHashes(String modelId) {
        return approved.computeIfAbsent(modelId, k -> ConcurrentHashMap.newKeySet());
    }

    private EffectSpec findEffect(String modelId, String effectId) {
        for (EffectSpec e : specEffects(modelId)) {
            if (e.id().equals(effectId)) return e;
        }
        return null;
    }

    private List<EffectSpec> specEffects(String modelId) {
        try {
            ModelSpec spec = service.getSpec(modelId);
            return spec.effects();
        } catch (RuntimeException e) {
            return List.of();   // model gone — nothing to gate
        }
    }
}
