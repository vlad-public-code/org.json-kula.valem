package org.json_kula.valem.api.composition;

import java.util.List;

/**
 * Live cross-model composition topology (composition architecture §6.2) — purely observational,
 * computed on demand from each model's spec + current {@code $io} link status + {@code lineage}. Powers
 * ops dashboards; never authoritative state.
 */
public record CompositionGraph(
        List<Node> nodes,
        List<LinkEdge> linkEdges,
        List<LineageEdge> lineageEdges) {

    /** A model in the graph. {@code class} is {@code local} for an in-process instance. */
    public record Node(String ref, String modelClass, boolean running) {}

    /** A runtime link edge, with the last-observed fire status read from the source's {@code $io}. */
    public record LinkEdge(String from, String to, String effectId, String kind, LastFire last) {}

    /** The last recorded fire of a link (null when it has not fired yet). */
    public record LastFire(String phase, String at, String error) {}

    /** A branch → template provenance edge, read from the branch's pinned lineage. */
    public record LineageEdge(String branch, String template) {}
}
