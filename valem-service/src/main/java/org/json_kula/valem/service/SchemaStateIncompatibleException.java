package org.json_kula.valem.service;

import org.json_kula.valem.core.engine.SchemaStateChecker;

import java.util.List;

/**
 * Thrown when a schema-changing spec evolution would leave existing state violating the new
 * schema (e.g. a {@code number → string} retype while the document still holds numbers). The
 * evolution is rejected up-front (HTTP 422) rather than committing state that fails its own
 * schema and surfacing a confusing error on a later, unrelated mutation.
 */
public class SchemaStateIncompatibleException extends RuntimeException {

    private final List<SchemaStateChecker.Incompatibility> incompatibilities;

    public SchemaStateIncompatibleException(String id, List<SchemaStateChecker.Incompatibility> incompatibilities) {
        super("Evolution of model '" + id + "' would strand " + incompatibilities.size()
                + " existing value(s) that violate the new schema: "
                + incompatibilities.stream()
                        .map(SchemaStateChecker.Incompatibility::message)
                        .limit(10)
                        .reduce((a, b) -> a + "; " + b).orElse(""));
        this.incompatibilities = List.copyOf(incompatibilities);
    }

    public List<SchemaStateChecker.Incompatibility> incompatibilities() {
        return incompatibilities;
    }
}
