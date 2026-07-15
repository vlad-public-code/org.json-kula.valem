package org.json_kula.valem.api.composition;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.LineageEntry;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.valem.service.ModelService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the live {@link CompositionGraph} on demand from the registered models: nodes from the
 * registry, link edges from each spec's {@code target} effects (with the last-fire status read out of
 * the source's {@code $io} sub-document), and lineage edges from each spec's pinned {@code lineage}.
 */
@Service
public class CompositionGraphService {

    private final ModelService service;

    public CompositionGraphService(ModelService service) {
        this.service = service;
    }

    public CompositionGraph build() {
        List<CompositionGraph.Node> nodes = new ArrayList<>();
        List<CompositionGraph.LinkEdge> linkEdges = new ArrayList<>();
        List<CompositionGraph.LineageEdge> lineageEdges = new ArrayList<>();

        for (String id : service.listModels()) {
            ModelSpec spec;
            try { spec = service.getSpec(id); }
            catch (RuntimeException e) { continue; }   // raced deletion

            nodes.add(new CompositionGraph.Node(id, "local", true));

            ObjectNode state = null;   // read lazily; only needed if the model has links
            for (EffectSpec e : spec.effects()) {
                if (e.target() == null || e.target().ref() == null || e.target().ref().isBlank()) continue;
                if (!ModelCoordinate.isValid(e.target().ref())) continue;
                String to = ModelCoordinate.parse(e.target().ref()).identity();
                String kind = e.target().isRead() ? "read" : "write";

                if (state == null) {
                    try { state = service.getState(id, null); }
                    catch (RuntimeException ex) { state = null; }
                }
                linkEdges.add(new CompositionGraph.LinkEdge(id, to, e.id(), kind, lastFire(state, e.statusPath())));
            }

            for (LineageEntry entry : spec.lineage()) {
                lineageEdges.add(new CompositionGraph.LineageEdge(id, entry.ref()));
            }
        }
        return new CompositionGraph(nodes, linkEdges, lineageEdges);
    }

    /** Reads {phase, at, error} from the source model's {@code $io} status sub-document at statusPath. */
    private static CompositionGraph.LastFire lastFire(ObjectNode state, String statusPath) {
        if (state == null || statusPath == null || statusPath.isBlank()) return null;
        JsonPointer ptr;
        try { ptr = PathConverter.toJsonPointer(statusPath); }
        catch (RuntimeException e) { return null; }
        JsonNode io = state.at(ptr);
        if (io == null || io.isMissingNode() || !io.isObject()) return null;
        return new CompositionGraph.LastFire(text(io, "phase"), text(io, "at"), text(io, "error"));
    }

    private static String text(JsonNode obj, String field) {
        JsonNode v = obj.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
