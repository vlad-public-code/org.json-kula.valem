package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A default-value rule: when a new container (array element, object, or the root document) matching
 * {@code path} is first created during a mutation, {@code expr} is evaluated and its resulting object
 * is deep-merged into the new container, filling only the fields the caller did not already provide.
 *
 * <p>{@code path} is a canonical container <b>address</b> — the document root {@code $}, an object
 * path ({@code $.customer}), or an array-element pattern ({@code $.items[*]}). It is not a scalar
 * field path. {@code expr} is a JSONata expression that must evaluate to an object; a non-object
 * result is ignored.
 *
 * <p>The expression is evaluated against the full document with two bindings:
 * <ul>
 *   <li>{@code $parent} — the JSON-tree parent of the new container (the containing array for an
 *       element, the containing object for an object, the root for {@code $}).</li>
 *   <li>{@code $self} — the new container node as populated by the caller before defaults are
 *       applied, so container-local defaults like {@code $self.qty * $self.unitPrice} are
 *       expressible.</li>
 * </ul>
 *
 * <p>A rule with {@code path == "$"} fires exactly once, when the document is first created at model
 * creation, and so replaces the former {@code initialState} seed map.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DefaultValueSpec(
        String path,
        String expr,
        String description
) {
    @JsonCreator
    public static DefaultValueSpec of(
            @JsonProperty(value = "path", required = true) String path,
            @JsonProperty(value = "expr", required = true) String expr,
            @JsonProperty("description")                    String description
    ) {
        return new DefaultValueSpec(path, expr, description);
    }
}
