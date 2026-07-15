package org.json_kula.valem.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Immutable deep copy of {@link ModelState} at a single point in time.
 *
 * <p>Used for two purposes:
 * <ul>
 *   <li><b>In-process rollback</b> — taken at {@link ModelState#beginTransaction()} and
 *       restored on {@link ModelState#rollback()}.</li>
 *   <li><b>Persistent snapshot</b> — serialised and stored externally for checkpoint/restore.</li>
 * </ul>
 *
 * <p>Blob data is not captured: blobs are content-addressed and immutable, so they
 * survive rollback without special handling.
 */
public record Snapshot(
        String modelId,
        String modelVersion,
        ObjectNode baseDoc,                  // deep copy at capture; but a Snapshot obtained via
                                             // ModelRuntime.lastCommittedSnapshot() is shared with
                                             // ModelHistory — treat as read-only unless you took it
                                             // via ModelState.snapshot() / ModelRuntime.snapshot()
        Map<String, JsonNode> derivedCache,  // unmodifiable copy
        Map<String, JsonNode> metaCache      // unmodifiable copy
) {
    /**
     * Returns a deep copy of the base document with all derived values spliced in,
     * equivalent to {@link ModelState#mergedDocument()} but for this historical snapshot.
     */
    public ObjectNode mergedDocument() {
        ObjectNode merged = baseDoc.deepCopy();
        for (Map.Entry<String, JsonNode> e : derivedCache.entrySet()) {
            // Length-aware splice — see ModelState.setDerivedInDoc; never grows arrays, so stale
            // derived entries beyond the current array length cannot create phantom elements.
            ModelState.setDerivedInDoc(merged, PathConverter.toSegments(e.getKey()), e.getValue());
        }
        return merged;
    }
}
