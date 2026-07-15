package org.json_kula.valem.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.persistence.SpecStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@link SpecStore} backed by PostgreSQL table {@code ss_specs}.
 *
 * <p>The spec is stored as JSONB. All SQL uses {@code ?::jsonb} to cast
 * the string parameter and {@code spec::text} to read it back.
 */
public final class PostgresSpecStore implements SpecStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresSpecStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresSpecStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc   = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void saveSpec(String modelId, ModelSpec spec) throws IOException {
        String json = mapper.writeValueAsString(spec);
        jdbc.update("""
                INSERT INTO ss_specs(model_id, spec, saved_at)
                VALUES(?, ?::jsonb, now())
                ON CONFLICT (model_id) DO UPDATE
                SET spec = excluded.spec, saved_at = now()
                """, modelId, json);
        log.debug("Saved spec for model '{}'", modelId);
    }

    @Override
    public Optional<ModelSpec> loadSpec(String modelId) throws IOException {
        try {
            String json = jdbc.queryForObject(
                    "SELECT spec::text FROM ss_specs WHERE model_id = ?",
                    String.class, modelId);
            return Optional.of(mapper.readValue(json, ModelSpec.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> modelIds() throws IOException {
        return jdbc.queryForList(
                "SELECT model_id FROM ss_specs ORDER BY model_id",
                String.class);
    }

    @Override
    public void delete(String modelId) throws IOException {
        jdbc.update("DELETE FROM ss_specs WHERE model_id = ?", modelId);
        log.debug("Deleted spec for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }
}
