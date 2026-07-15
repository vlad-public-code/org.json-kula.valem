package org.json_kula.valem.persistence.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.persistence.SpecStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link SpecStore} backed by Redis strings and a Redis Set of model IDs.
 *
 * <p>Key scheme:
 * <ul>
 *   <li>{@code valem:spec:{modelId}} — JSON-serialised {@link ModelSpec}</li>
 *   <li>{@code valem:models} — Redis Set of all registered model IDs</li>
 * </ul>
 */
public final class RedisSpecStore implements SpecStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSpecStore.class);

    static final String KEY_PREFIX_SPEC = "valem:spec:";
    static final String KEY_MODELS      = "valem:models";

    private final RedisCommands<String, String> commands;
    private final ObjectMapper                  mapper;

    public RedisSpecStore(StatefulRedisConnection<String, String> conn, ObjectMapper mapper) {
        this.commands = conn.sync();
        this.mapper   = mapper;
    }

    @Override
    public void saveSpec(String modelId, ModelSpec spec) throws IOException {
        commands.set(KEY_PREFIX_SPEC + modelId, mapper.writeValueAsString(spec));
        commands.sadd(KEY_MODELS, modelId);
        log.debug("Saved spec for model '{}'", modelId);
    }

    @Override
    public Optional<ModelSpec> loadSpec(String modelId) throws IOException {
        String json = commands.get(KEY_PREFIX_SPEC + modelId);
        if (json == null) return Optional.empty();
        return Optional.of(mapper.readValue(json, ModelSpec.class));
    }

    @Override
    public List<String> modelIds() throws IOException {
        List<String> ids = new ArrayList<>(commands.smembers(KEY_MODELS));
        ids.sort(String::compareTo);
        return ids;
    }

    @Override
    public void delete(String modelId) throws IOException {
        commands.del(KEY_PREFIX_SPEC + modelId);
        commands.srem(KEY_MODELS, modelId);
        log.debug("Deleted spec for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }
}
