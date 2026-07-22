package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Fallback binding for a {@code type} none of the built-in component types claim.
 *
 * <p>Unlike every other permit, this one imposes no field set: it keeps the component's raw
 * JSON verbatim, so a custom type may carry any property at all — including properties no
 * built-in type declares — without them being silently dropped at parse time. The common
 * fields the evaluation pipeline reads are projected out of that raw node; everything else is
 * reachable through {@link #property(String)}.
 *
 * <p>{@code ViewEvaluator} renders these as a basic input, which is what an unrecognised
 * {@code type} has always resolved to.
 */
public record UnknownComponentSpec(@JsonValue JsonNode raw) implements ComponentSpec {

    public UnknownComponentSpec {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("ComponentSpec: expected a JSON object");
        }
        ComponentSpec.requireIdentity(text(raw, "id"), text(raw, "type"));
    }

    /** Binds the whole component object, so no property is lost whatever the type carries. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UnknownComponentSpec of(JsonNode raw) {
        return new UnknownComponentSpec(raw);
    }

    @Override public String   id()       { return text(raw, "id"); }
    @Override public String   type()     { return text(raw, "type"); }
    @Override public String   bind()     { return text(raw, "bind"); }
    @Override public JsonNode visible()  { return property("visible"); }
    @Override public JsonNode enabled()  { return property("enabled"); }
    @Override public JsonNode readOnly() { return property("readOnly"); }
    @Override public JsonNode required() { return property("required"); }

    public String label()       { return text(raw, "label"); }
    public String placeholder() { return text(raw, "placeholder"); }
    public String helperText()  { return text(raw, "helperText"); }
    public String tooltip()     { return text(raw, "tooltip"); }

    public EventHandler onChange() { return handler("onChange"); }
    public EventHandler onClick()  { return handler("onClick"); }

    /** Any property the component carried, known or not; {@code null} when absent. */
    public JsonNode property(String name) {
        JsonNode n = raw.get(name);
        return n == null || n.isNull() ? null : n;
    }

    private EventHandler handler(String name) {
        JsonNode n = property(name);
        if (n == null || !n.isObject()) return null;
        return new EventHandler(text(n, "mutations"), text(n, "navigate"));
    }

    private static String text(JsonNode node, String name) {
        JsonNode n = node.get(name);
        return n == null || !n.isTextual() ? null : n.asText();
    }
}
