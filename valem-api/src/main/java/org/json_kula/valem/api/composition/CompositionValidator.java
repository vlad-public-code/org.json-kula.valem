package org.json_kula.valem.api.composition;

import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the <b>cross-model link topology</b> at load/create/evolve time (composition architecture
 * §5.2). Builds a directed graph over the link edges of a candidate spec together with the already-known
 * specs, then enforces:
 *
 * <ul>
 *   <li><b>Cycle guard</b> — a static link cycle is legal <em>iff every edge on it carries both a
 *       {@code statusPath} and a {@code dedupeKey}</em> (edge-trigger + CAS is what terminates it). An
 *       unguarded edge on a cycle is an {@link CompositionException.UnguardedCycle}.</li>
 *   <li><b>Existence</b> (optional, when not lazy-binding) — every {@code target.ref} resolves to a
 *       known model; otherwise {@link CompositionException.UnresolvedLinkTarget}.</li>
 * </ul>
 *
 * <p>Pure over the supplied spec set — the api layer feeds it the running models plus the candidate.
 * Reference <em>shape</em> (coordinate grammar, one-locator, address canonicality) is already enforced
 * by the core {@code ModelSpecValidator}; this is the graph-level check core cannot do.
 */
public final class CompositionValidator {

    private final boolean lazyBinding;

    public CompositionValidator(boolean lazyBinding) {
        this.lazyBinding = lazyBinding;
    }

    /** A directed link edge {@code from → to} (model identities), and whether it is edge-triggered. */
    public record LinkEdge(String from, String to, String effectId, boolean guarded) {}

    /**
     * Validates the topology formed by {@code candidate} plus {@code existing} (the other registered
     * models). Throws on the first violation.
     */
    public void validate(ModelSpec candidate, List<ModelSpec> existing) {
        List<ModelSpec> all = new ArrayList<>(existing.size() + 1);
        // The candidate replaces any same-id existing spec (an evolve re-validates the new shape).
        for (ModelSpec s : existing) {
            if (!s.id().equals(candidate.id())) all.add(s);
        }
        all.add(candidate);

        Set<String> known = new HashSet<>();
        for (ModelSpec s : all) known.add(identity(s.id()));

        List<LinkEdge> edges = new ArrayList<>();
        for (ModelSpec s : all) edges.addAll(linkEdges(s));

        // Existence — only for the candidate's own edges (existing models were validated when created).
        if (!lazyBinding) {
            for (LinkEdge e : linkEdges(candidate)) {
                if (!known.contains(e.to())) {
                    throw new CompositionException.UnresolvedLinkTarget(
                            "link '" + e.effectId() + "' in model '" + e.from()
                            + "' targets unknown model '" + e.to()
                            + "' (enable composition.lazy-binding to defer)");
                }
            }
        }

        // Cycle guard — every edge lying on a cycle must be guarded.
        Map<String, List<LinkEdge>> adj = new HashMap<>();
        for (LinkEdge e : edges) adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
        for (LinkEdge e : edges) {
            if (!e.guarded() && onCycle(e, adj)) {
                throw new CompositionException.UnguardedCycle(
                        "link '" + e.effectId() + "' (" + e.from() + " -> " + e.to()
                        + ") lies on a cycle but lacks a statusPath + dedupeKey guard; every edge on a "
                        + "link cycle must be edge-triggered so propagation terminates");
            }
        }
    }

    /** The link edges a spec declares (server effects carrying a {@code target.ref}). */
    public static List<LinkEdge> linkEdges(ModelSpec spec) {
        List<LinkEdge> out = new ArrayList<>();
        String from = identity(spec.id());
        for (EffectSpec e : spec.effects()) {
            if (e.target() == null || e.target().ref() == null || e.target().ref().isBlank()) continue;
            if (!ModelCoordinate.isValid(e.target().ref())) continue;   // shape already erred in core
            String to = ModelCoordinate.parse(e.target().ref()).identity();
            boolean guarded = e.statusPath() != null && !e.statusPath().isBlank()
                    && e.dedupeKey() != null && !e.dedupeKey().isBlank();
            out.add(new LinkEdge(from, to, e.id(), guarded));
        }
        return out;
    }

    /** True if the edge {@code from → to} lies on a cycle: i.e. {@code to} can reach {@code from}. */
    private static boolean onCycle(LinkEdge edge, Map<String, List<LinkEdge>> adj) {
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(edge.to());
        while (!stack.isEmpty()) {
            String node = stack.pop();
            if (node.equals(edge.from())) return true;
            if (!seen.add(node)) continue;
            for (LinkEdge next : adj.getOrDefault(node, List.of())) {
                stack.push(next.to());
            }
        }
        return false;
    }

    private static String identity(String id) {
        return ModelCoordinate.isValid(id) ? ModelCoordinate.parse(id).identity() : id;
    }
}
