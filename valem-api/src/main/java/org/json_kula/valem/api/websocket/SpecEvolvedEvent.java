package org.json_kula.valem.api.websocket;

/**
 * JSON-serialisable payload pushed to WebSocket subscribers after a successful
 * {@code POST /models/{id}/spec/evolve}. Discriminated from {@link ChangeEvent} by {@code kind} so a
 * subscriber can tell "state changed" (a mutation) from "spec changed" (an evolution) on the same
 * per-model topic.
 *
 * <p>The event deliberately carries only the new version, not the spec/state itself — a subscriber
 * (the sandbox browser) re-fetches {@code GET /models/{id}/spec} (and view/state) as the source of
 * truth, exactly as it already does after its own evolve calls.
 *
 * @param kind    discriminator; always {@code "spec-evolved"}
 * @param modelId the model whose spec changed
 * @param version the new spec version
 */
public record SpecEvolvedEvent(String kind, String modelId, String version) {
    public SpecEvolvedEvent(String modelId, String version) {
        this("spec-evolved", modelId, version);
    }
}
