package org.json_kula.valem.api.reference;

import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;

/**
 * A resolver's lockfile record: the spec a coordinate resolved to, plus the <em>exact</em> version +
 * digest + repository it came from (references design §4.1). Pinning the exact resolution makes
 * re-materialization and audit reproducible even when the request was a range or a mutable name.
 */
public record ResolvedSpec(
        ModelSpec spec,
        ModelCoordinate resolved,   // name@exactVersion
        String digest,              // sha256: content digest of the served spec
        String repoId) {
}
