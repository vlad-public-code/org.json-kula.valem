package org.json_kula.valem.core.engine;

import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.core.graph.CompiledModel;

/**
 * Builds the base {@link JsonataBindings} shared by every expression evaluation in a model: the
 * named {@code constants} exposed as {@code $const}. Evaluators that need additional bindings (e.g.
 * {@code $parent}/{@code $self}) chain onto the returned builder.
 */
final class EvalBindings {

    private EvalBindings() {}

    /** A fresh bindings builder with {@code $const} bound to the model's constants object. */
    static JsonataBindings forModel(CompiledModel model) {
        return new JsonataBindings().bindValue("const", model.constantsNode());
    }
}
