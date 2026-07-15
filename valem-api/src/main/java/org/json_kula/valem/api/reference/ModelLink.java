package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.model.ModelCoordinate;

import java.util.List;
import java.util.Map;

/**
 * A uniform, transport-independent handle a runtime composition link fires against (references design
 * §4.2). The {@code local} / {@code mcp} / {@code http} transport disappears behind this interface:
 * the composition effect executor calls {@link #mutate} (a <b>write-link</b>) or {@link #getField} (a
 * <b>read-link</b>, no mutation) and never sees where the target model lives.
 *
 * <p>M2 ships the {@code local} implementation ({@link LocalModelLink}); remote transports arrive in
 * M6.
 */
public interface ModelLink {

    /** The exact coordinate this link resolved to. */
    ModelCoordinate coordinate();

    /**
     * Write-link: applies {@code pathValues} (canonical address → value) to the target model, runs the
     * target's reactive cycle, and returns its post-commit reply (bound as {@code $response} in the
     * source's {@code response.set}). Acquires only the target's lock.
     */
    MutationReply mutate(Map<String, JsonNode> pathValues);

    /**
     * Read-link: returns the value at {@code path} (a canonical address) in the target without mutating
     * it — no target mutation, no target audit record (the sole write is a LAZY derivation computing
     * into the target's cache under its lock, still semantically a read).
     */
    JsonNode getField(String path);

    /**
     * What a write-link's {@link #mutate} returns. {@code result} is the target's post-commit
     * projection (its merged document by default) — the source binds it as {@code $response} and shapes
     * it through {@code response.set}. {@code derivedUpdated} lists the target paths the write
     * recomputed (observability only).
     */
    record MutationReply(JsonNode result, List<String> derivedUpdated) {}
}
