package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The composition {@code target} block on a {@code server} effect — an alternative to
 * {@code request.url} that names another model by coordinate instead of a URL
 * (model-composition-architecture §3.1, §9.1). Two variants, mutually exclusive:
 *
 * <ul>
 *   <li><b>write-link</b> — {@code { ref, path }} (+ a sibling {@code body} on the effect): mutate the
 *       target at {@code path} and fold its reply back.</li>
 *   <li><b>read-link</b> — {@code { ref, read, watch? }}: read the value at {@code read} without
 *       mutating the target (no body → no target mutation, no target audit record).</li>
 * </ul>
 *
 * <p>{@code binding} is an optional escape hatch to reach an external, non-registered model with an
 * explicit transport/locator instead of the repository chain.
 *
 * <p>Pure descriptor — the shell resolves {@code ref} through the repository chain and picks
 * {@code mutate} vs {@code getField}. The core never resolves a coordinate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TargetSpec(
        String ref,          // model coordinate (parsed by ModelCoordinate at the shell)
        String path,         // write-link: canonical writable address into the target
        String read,         // read-link: canonical readable address into the target
        Boolean watch,       // read-link: standing subscription (later); default false
        Binding binding      // optional external-model escape hatch
) {
    @JsonCreator
    public static TargetSpec of(
            @JsonProperty("ref")     String ref,
            @JsonProperty("path")    String path,
            @JsonProperty("read")    String read,
            @JsonProperty("watch")   Boolean watch,
            @JsonProperty("binding") Binding binding) {
        return new TargetSpec(ref, path, read, watch, binding);
    }

    /** {@code true} for the read-link form (a {@code read} path is present). */
    @JsonIgnore
    public boolean isRead() {
        return read != null && !read.isBlank();
    }

    /** {@code true} for the write-link form (a {@code path} is present and no {@code read}). */
    @JsonIgnore
    public boolean isWrite() {
        return !isRead() && path != null && !path.isBlank();
    }

    /** {@code watch} with a false default. */
    @JsonIgnore
    public boolean watchOrFalse() {
        return watch != null && watch;
    }

    /** Escape hatch for an external, non-registered model — an explicit transport + locator. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Binding(String transport, String locator, String egressProfile) {
        @JsonCreator
        public static Binding of(
                @JsonProperty("transport")     String transport,
                @JsonProperty("locator")       String locator,
                @JsonProperty("egressProfile") String egressProfile) {
            return new Binding(transport, locator, egressProfile);
        }
    }
}
