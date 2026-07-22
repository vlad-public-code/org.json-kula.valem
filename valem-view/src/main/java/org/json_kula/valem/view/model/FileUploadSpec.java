package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code fileUploadField} — uploads to {@code /blobs} and stores a BlobRef in the bound field.
 *
 * <p>{@code minFiles}/{@code maxFiles}/{@code minSize}/{@code maxSize}/{@code allowedMediaTypes}
 * fall back to the corresponding metaDerivation on the bind path when unset here.
 */
public record FileUploadSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        JsonNode enabled,
        JsonNode readOnly,
        JsonNode required,
        String bind,
        String helperText,
        String tooltip,
        String accept,
        Boolean multiple,
        Integer minFiles,
        Integer maxFiles,
        Long minSize,
        Long maxSize,
        String allowedMediaTypes,
        EventHandler onChange
) implements ComponentSpec {
    public FileUploadSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
