package org.json_kula.valem.persistence;

/**
 * Composite persistence contract that combines {@link SpecStore} and {@link StateStore}.
 *
 * <p>Exists for backward compatibility with existing injection points in
 * {@code valem-api} that declare {@code ModelStore}. Adapter classes may implement
 * this interface directly (when spec and state share the same backend) or be composed via
 * {@code CompositeModelStore} (when backed by separate stores).
 *
 * <p>New code should prefer injecting {@link SpecStore} and {@link StateStore} individually.
 */
public interface ModelStore extends SpecStore, StateStore {
    // No additional methods. Composite marker interface only.
}
