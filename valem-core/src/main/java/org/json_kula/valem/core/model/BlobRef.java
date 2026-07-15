package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * In-state representation of a binary field. Binary bytes live in the BlobStore;
 * the state tree holds only this lightweight reference.
 *
 * JSON shape: {"$blobId": "sha256:...", "$mediaType": "image/png", "$bytes": 5242880}
 */
public record BlobRef(
        @JsonProperty("$blobId")    String blobId,
        @JsonProperty("$mediaType") String mediaType,
        @JsonProperty("$bytes")     long bytes) {

    public JsonNode toJsonNode() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("$blobId",     blobId);
        node.put("$mediaType",  mediaType);
        node.put("$bytes",      bytes);
        return node;
    }

    public static BlobRef fromJsonNode(JsonNode node) {
        return new BlobRef(
                node.get("$blobId").asText(),
                node.get("$mediaType").asText(),
                node.get("$bytes").asLong()
        );
    }

    public static boolean isBlobRef(JsonNode node) {
        return node != null
                && node.isObject()
                && node.has("$blobId")
                && node.has("$mediaType")
                && node.has("$bytes");
    }
}

