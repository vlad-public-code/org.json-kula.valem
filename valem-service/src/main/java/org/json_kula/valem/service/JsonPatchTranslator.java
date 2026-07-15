package org.json_kula.valem.service;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.state.PathConverter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates RFC 6902 JSON Patch operations into Valem path/value mutation pairs
 * suitable for {@link org.json_kula.valem.core.engine.ModelRuntime#mutate}.
 */
public class JsonPatchTranslator {

    private JsonPatchTranslator() {}

    /**
     * @param patchOps   the RFC 6902 array of operation objects
     * @param patchedDoc the source document after the patch has been applied
     * @return ordered map of Valem dot-notation paths to their new values
     */
    public static Map<String, JsonNode> translate(JsonNode patchOps, ObjectNode patchedDoc) {
        Map<String, JsonNode> mutations = new LinkedHashMap<>();
        for (JsonNode op : patchOps) {
            String opType = op.path("op").asText().toLowerCase(Locale.ROOT);
            if ("test".equals(opType)) continue;

            String pathStr = op.path("path").asText();

            switch (opType) {
                case "replace" -> addPointerMutation(pathStr, patchedDoc, mutations);
                case "add" -> {
                    String last = lastPtrSegment(pathStr);
                    if (last.equals("-") || isDigits(last)) {
                        String parentPtr = parentPointer(pathStr);
                        JsonNode arr = pointerAt(patchedDoc, parentPtr);
                        if (arr.isArray()) {
                            int startIdx = last.equals("-") ? arr.size() - 1 : Integer.parseInt(last);
                            for (int i = startIdx; i < arr.size(); i++) {
                                addLeafMutations(parentPtr + "/" + i, arr.get(i), mutations);
                            }
                            addPointerMutation(parentPtr, patchedDoc, mutations);
                        }
                    } else {
                        addPointerMutation(pathStr, patchedDoc, mutations);
                    }
                }
                case "remove" -> {
                    String last = lastPtrSegment(pathStr);
                    if (isDigits(last)) {
                        String parentPtr = parentPointer(pathStr);
                        JsonNode arr = pointerAt(patchedDoc, parentPtr);
                        if (arr.isArray()) {
                            int removedIdx = Integer.parseInt(last);
                            for (int i = removedIdx; i < arr.size(); i++) {
                                addLeafMutations(parentPtr + "/" + i, arr.get(i), mutations);
                            }
                            addPointerMutation(parentPtr, patchedDoc, mutations);
                        }
                    } else if (!last.isEmpty()) {
                        mutations.put(toPath(pathStr), NullNode.getInstance());
                    }
                }
                case "move", "copy" -> {
                    String fromPtr = op.path("from").asText();
                    for (String ptr : List.of(fromPtr, pathStr)) {
                        String last = lastPtrSegment(ptr);
                        if (last.equals("-") || isDigits(last)) {
                            String parentPtr = parentPointer(ptr);
                            JsonNode arr = pointerAt(patchedDoc, parentPtr);
                            if (arr.isArray()) {
                                for (int i = 0; i < arr.size(); i++) {
                                    addLeafMutations(parentPtr + "/" + i, arr.get(i), mutations);
                                }
                                addPointerMutation(parentPtr, patchedDoc, mutations);
                            }
                        } else {
                            addPointerMutation(ptr, patchedDoc, mutations);
                        }
                    }
                }
            }
        }
        return mutations;
    }

    private static boolean isDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static String lastPtrSegment(String ptr) {
        int idx = ptr.lastIndexOf('/');
        return idx < 0 ? ptr : ptr.substring(idx + 1);
    }

    private static String parentPointer(String ptr) {
        int idx = ptr.lastIndexOf('/');
        return idx <= 0 ? "" : ptr.substring(0, idx);
    }

    private static JsonNode pointerAt(ObjectNode doc, String ptr) {
        return ptr.isEmpty() ? doc : doc.at(ptr);
    }

    private static void addPointerMutation(String ptr, ObjectNode doc, Map<String, JsonNode> mutations) {
        if (ptr.isEmpty()) return;
        JsonNode value = doc.at(ptr);
        if (!value.isMissingNode()) {
            mutations.put(toPath(ptr), value);
        }
    }

    private static void addLeafMutations(String ptr, JsonNode node, Map<String, JsonNode> mutations) {
        if (node == null || node.isMissingNode()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    addLeafMutations(ptr + "/" + e.getKey(), e.getValue(), mutations));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                addLeafMutations(ptr + "/" + i, node.get(i), mutations);
            }
        } else if (!ptr.isEmpty()) {
            mutations.put(toPath(ptr), node);
        }
    }

    private static String toPath(String ptr) {
        return PathConverter.fromJsonPointer(JsonPointer.compile(ptr));
    }
}
