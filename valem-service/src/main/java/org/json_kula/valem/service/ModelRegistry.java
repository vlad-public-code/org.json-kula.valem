package org.json_kula.valem.service;

import org.json_kula.valem.core.engine.ModelRuntime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process store for active {@link ModelRuntime} instances.
 *
 * <p>Thread-safe: lookups and modifications use a {@link ConcurrentHashMap}.
 * Individual runtimes are not thread-safe; callers must synchronize externally
 * when issuing mutations.
 */
public class ModelRegistry {

    private final ConcurrentHashMap<String, ModelRuntime> runtimes = new ConcurrentHashMap<>();

    public void register(String id, ModelRuntime runtime) {
        runtimes.put(id, runtime);
    }

    /**
     * Registers a runtime only if no entry already exists for {@code id}.
     *
     * @return {@code true} if the runtime was inserted, {@code false} if a runtime was already present
     */
    public boolean registerIfAbsent(String id, ModelRuntime runtime) {
        return runtimes.putIfAbsent(id, runtime) == null;
    }

    public Optional<ModelRuntime> find(String id) {
        return Optional.ofNullable(runtimes.get(id));
    }

    public boolean remove(String id) {
        return runtimes.remove(id) != null;
    }

    public boolean contains(String id) {
        return runtimes.containsKey(id);
    }

    public List<String> allIds() {
        return runtimes.keySet().stream().sorted().toList();
    }

    public int size() { return runtimes.size(); }
}
