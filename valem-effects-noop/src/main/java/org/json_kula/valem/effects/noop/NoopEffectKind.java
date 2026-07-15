package org.json_kula.valem.effects.noop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.engine.spi.EffectEvalContext;
import org.json_kula.valem.core.engine.spi.EffectKind;
import org.json_kula.valem.core.engine.spi.EffectValidationContext;
import org.json_kula.valem.core.model.EffectSpec;

import java.util.Map;

/**
 * Reference {@link EffectKind} for the {@code noop} executor — a no-I/O demonstration of the pluggable
 * effects SPI. It reuses the existing {@link EffectSpec} {@code payload} field as a map of JSONata
 * expressions to <em>echo</em>: each is evaluated against current state into the request {@code params},
 * and the shell {@link NoopEffectExecutor} folds them back through {@code response.set} (which sees the
 * evaluated payload as {@code $response}). Purely a teaching example — a real plugin would do actual I/O.
 *
 * <p>Discovered at runtime via {@link java.util.ServiceLoader} (see
 * {@code META-INF/services/org.json_kula.valem.core.engine.spi.EffectKind}); no core edits needed
 * to add it.
 */
public final class NoopEffectKind implements EffectKind {

    public static final String KIND = "noop";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void validate(EffectSpec effect, String location, EffectValidationContext ctx) {
        if (effect.payload().isEmpty()) {
            ctx.error(location, "noop-executor effect requires a non-empty payload to echo");
        }
        for (Map.Entry<String, String> e : effect.payload().entrySet()) {
            ctx.validateExpr(e.getValue(), location + ".payload." + e.getKey());
        }
    }

    @Override
    public EffectRequest.Plugin resolve(EffectSpec effect, EffectEvalContext ctx, JsonNode dedupeKey) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, String> e : effect.payload().entrySet()) {
            JsonNode v = ctx.eval(e.getValue());
            params.set(e.getKey(), v != null ? v : NullNode.instance);
        }
        return new EffectRequest.Plugin(
                KIND, effect.id(), effect.statusPath(), dedupeKey, effect.responseSet(), params);
    }
}
