package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Covers: image.
 *
 * <p>{@code src} is the resolved string; {@code value} is kept as well because when the image is
 * bound to an upload the bound value is a {@code BlobRef} object, and only the client knows how
 * to turn a {@code $blobId} into a URL it can actually fetch.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluatedImage(
        String id,
        String type,
        String label,
        String bind,
        JsonNode value,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = BooleanTrueFilter.class)
        boolean visible,
        String src,
        String alt,
        String width,
        String height,
        String fit
) implements EvaluatedComponent {}
