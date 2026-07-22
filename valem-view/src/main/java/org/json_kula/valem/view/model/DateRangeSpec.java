package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code dateRangeField} — two coupled dates written to two separate paths.
 *
 * <p>The pair is {@code bindFrom} / {@code bindTo} rather than one {@code bind} to an
 * {@code {from,to}} object, because the two ends are almost always already separate fields with
 * their own derivations and constraints ({@code endDate >= startDate} is a constraint, not a
 * widget rule). A single object bind would force every such spec to reach inside it.
 *
 * <p>{@code bind} is therefore not the value — it is the meta-inheritance anchor only, and is
 * usually left unset. {@code minDate} / {@code maxDate} bound the picker; they are authored
 * hints, and the model's own constraints remain the thing that actually rejects a bad range.
 */
public record DateRangeSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        JsonNode enabled,
        JsonNode readOnly,
        JsonNode required,
        String bind,
        String bindFrom,
        String bindTo,
        String fromLabel,
        String toLabel,
        String helperText,
        String tooltip,
        String minDate,
        String maxDate,
        EventHandler onChange
) implements ComponentSpec {
    public DateRangeSpec {
        ComponentSpec.requireIdentity(id, type);
    }
}
