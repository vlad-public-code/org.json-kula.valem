package org.json_kula.valem.core.engine;

import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.core.graph.CompiledModel;

import java.util.Set;

/**
 * Builds the base {@link JsonataBindings} shared by every expression evaluation in a model: the
 * named {@code constants} exposed as {@code $const}, plus everything the model's {@code library}
 * exports. Evaluators that need additional bindings (e.g. {@code $parent}/{@code $self}) chain onto
 * the returned builder.
 */
public final class EvalBindings {

    private EvalBindings() {}

    /**
     * Names Valem itself binds during evaluation. A library export may not use one of these — it
     * would be shadowed by, or would shadow, an engine binding depending on the evaluator, which is
     * the kind of difference nobody should have to reason about.
     *
     * <p>Kept here, next to the code that binds them, so a new engine binding cannot be added
     * without the validator that reads this set learning about it.
     */
    public static final Set<String> RESERVED_NAMES =
            Set.of("const", "parent", "self", "response", "now", "status");

    /**
     * A fresh bindings builder with {@code $const} bound to the model's constants object and every
     * library export bound by name.
     *
     * <p>{@code $const} is bound <b>unconditionally</b>, even when the model declares no constants:
     * a library function that reads {@code $const} resolves it against the calling evaluation, not
     * against the bindings it was defined with, so an evaluator that skipped the binding would make
     * that function silently return nothing.
     */
    public static JsonataBindings forModel(CompiledModel model) {
        JsonataBindings bindings = new JsonataBindings()
                .bindValue("const", model.constantsNode())
                .bindFunctions(model.libraryFunctions());
        model.libraryConstants().forEach(bindings::bindValue);
        return bindings;
    }
}
