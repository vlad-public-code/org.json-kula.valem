package org.json_kula.valem.api.persistence;

/**
 * Backward-compatible persistence contract used by controllers and {@code ModelLoader}.
 *
 * <p>This interface now extends {@link org.json_kula.valem.persistence.SpecStore} and
 * {@link org.json_kula.valem.persistence.StateStore} from {@code valem-persistence-api}.
 * New code should inject those interfaces directly; this type exists so existing Spring beans
 * and injection points do not need to change.
 *
 * @deprecated Prefer {@link org.json_kula.valem.persistence.ModelStore} from
 *             {@code valem-persistence-api}.
 */
@Deprecated
public interface ModelStore
        extends org.json_kula.valem.persistence.SpecStore,
                org.json_kula.valem.persistence.StateStore {
    // No additional methods.
}
