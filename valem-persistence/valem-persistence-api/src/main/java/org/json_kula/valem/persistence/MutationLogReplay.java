package org.json_kula.valem.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.state.ModelState;
import org.json_kula.valem.core.state.PathConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Applies an incremental mutation-log patch (RFC 6902) onto a baseline {@code baseDoc} using the
 * same <b>create-as-you-go</b> semantics as the live {@link ModelState#setValue} write path.
 *
 * <p>This exists because the persisted mutation log records ordinary {@code add}/{@code remove} ops
 * produced from the mutation map (see {@code ModelController.toRfc6902Patch}). A live mutation such
 * as {@code $.items[0].price = 5} auto-creates the intermediate {@code items} array and element
 * object, but a strict RFC 6902 {@code add} at {@code /items/0/price} <em>fails</em> when those
 * parents are absent from the baseline. The old per-store replay caught that failure and
 * <em>silently skipped the record</em> — quietly losing the mutation and letting reloaded state
 * diverge from what the user committed (audit stS2 / F-T5).
 *
 * <p>Replaying through this helper instead reconstructs the nested path exactly like the live write:
 * <ul>
 *   <li>{@code add} / {@code replace} → set the value, creating intermediate objects/arrays
 *       (a numeric segment creates/extends an array);</li>
 *   <li>{@code remove} → delete the leaf if present, otherwise a no-op;</li>
 *   <li>any other op type (never emitted by Valem, so only seen on corruption) → logged and
 *       skipped, never thrown.</li>
 * </ul>
 *
 * <p>The mutation log carries {@code baseDoc} changes only; derived/meta caches are recomputed by
 * {@code ModelRuntime} after a cold restart.
 */
public final class MutationLogReplay {

    private static final Logger log = LoggerFactory.getLogger(MutationLogReplay.class);

    private MutationLogReplay() {}

    /**
     * Applies every op in {@code patch} to {@code baseDoc} in order and returns {@code baseDoc}
     * (mutated in place). A {@code null} or non-array {@code patch} is a no-op.
     */
    public static ObjectNode apply(ObjectNode baseDoc, JsonNode patch) {
        if (baseDoc == null || patch == null || !patch.isArray()) return baseDoc;
        for (JsonNode op : patch) {
            String type = op.path("op").asText("");
            List<String> segments = PathConverter.segmentsFromPointer(op.path("path").asText(""));
            if (segments.isEmpty()) {
                if (!type.isEmpty()) {
                    log.warn("Skipping mutation-log op '{}' targeting the document root", type);
                }
                continue;
            }
            switch (type) {
                case "add", "replace" -> ModelState.applyAddOrReplace(baseDoc, segments, valueOf(op));
                case "remove"         -> ModelState.applyRemove(baseDoc, segments);
                default -> log.warn("Skipping unsupported mutation-log op '{}' at {}",
                        type, op.path("path").asText());
            }
        }
        return baseDoc;
    }

    private static JsonNode valueOf(JsonNode op) {
        JsonNode value = op.get("value");
        return value != null ? value : NullNode.instance;
    }
}
