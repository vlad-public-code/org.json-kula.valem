package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.service.ModelService;

import java.util.List;
import java.util.Map;

/**
 * The {@code local} (in-process) {@link ModelLink}: calls {@link ModelService} directly — no
 * serialization, no auth, no egress (references design §4.1). Acquires only the <em>target's</em> lock
 * on each call; the deadlock-avoidance invariant (composition §8.1) is upheld by the caller invoking
 * this off the source model's lock, from the post-commit effect sink on a virtual thread.
 */
final class LocalModelLink implements ModelLink {

    private final ModelService service;
    private final String targetId;
    private final ModelCoordinate coordinate;

    LocalModelLink(ModelService service, String targetId, ModelCoordinate coordinate) {
        this.service = service;
        this.targetId = targetId;
        this.coordinate = coordinate;
    }

    @Override
    public ModelCoordinate coordinate() {
        return coordinate;
    }

    @Override
    public MutationReply mutate(Map<String, JsonNode> pathValues) {
        ModelService.MutationOutcome outcome = service.mutate(targetId, pathValues);
        // Default projection: the target's post-commit merged document, so the source can bind
        // $response.<anything> the target computed. (A narrower projection is a later refinement.)
        JsonNode merged = service.getState(targetId, null);
        List<String> derived = outcome.result() != null ? outcome.result().derivedUpdated() : List.of();
        return new MutationReply(merged, derived);
    }

    @Override
    public JsonNode getField(String path) {
        return service.getFieldValue(targetId, path);
    }
}
