package org.json_kula.valem.core.engine.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The pure evaluation surface handed to an {@link EffectKind#resolve} implementation. It exposes exactly
 * the JSONata helpers the built-in effect kinds use to turn declared expressions into data, so a plugin
 * resolves its spec fragment the same way the core does — <b>without performing any I/O</b> and without
 * reaching into engine internals ({@code ExpressionCache}, {@code JsonataBindings}). All expressions are
 * evaluated against the current merged document with the model's standard bindings ({@code $const}, …).
 */
public interface EffectEvalContext {

    /** The merged document the effect fired against (base fields spliced with derived values). */
    ObjectNode context();

    /** Evaluates a JSONata expression against {@link #context()}; returns {@code null} on error/empty. */
    JsonNode eval(String expr);

    /** Evaluates {@code expr} as a boolean (truthy string/number coerced), {@code false} on error/empty. */
    boolean evalBoolean(String expr);

    /** Replaces every {@code { expr }} segment in {@code template} with the rendered JSONata result. */
    String interpolate(String template);
}
