package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code image} — a picture, from a literal {@code src}, a JSONata expression, or the bound path.
 *
 * <p>When {@code src} is unset the bound value supplies it, which is what makes this the display
 * half of {@code fileUploadField}: an upload stores a {@code BlobRef}, and binding an
 * {@code image} to the same path shows what was uploaded. The renderer resolves a
 * {@code {$blobId}} value to its {@code /blobs} URL; any other value is used as a URL verbatim.
 *
 * <p>{@code alt} is a plain string and not optional in practice — an image with no alternative
 * text is unreadable to anyone using a screen reader, and a generated spec has no second chance
 * to add it.
 */
public record ImageSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        JsonNode src,
        String alt,
        String width,
        String height,
        String fit
) implements ComponentSpec {
    public ImageSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
