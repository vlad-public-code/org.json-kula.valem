package org.json_kula.valem.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Verifies the fail-clear behaviour of the {@code PersistenceProvider} SPI resolver (Phase 3):
 * selecting a backend for which no provider is on the classpath must fail with an actionable
 * "add the jar" message rather than an obscure wiring error. An unrecognised backend name
 * exercises the identical resolution branch as a genuinely absent adapter jar — in both cases the
 * type is simply not among the discovered providers.
 */
class MissingBackendResolutionTest {

    @SuppressWarnings("unchecked")
    private static StorageConfig config(MockEnvironment env) {
        ObjectProvider<DataSource> noDataSource = mock(ObjectProvider.class);
        return new StorageConfig(env, new ObjectMapper(), noDataSource);
    }

    @Test
    void unknown_spec_state_backend_fails_with_add_the_jar_message() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("valem.storage.type", "cassandra");

        assertThatThrownBy(() -> config(env).modelStore())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spec backend 'cassandra'")
                .hasMessageContaining("valem-persistence-cassandra")
                .hasMessageContaining("Available backends:");
    }

    @Test
    void unknown_blob_backend_fails_with_add_the_jar_message() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("valem.storage.blob-type", "gcs");

        assertThatThrownBy(() -> config(env).blobStore())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blob backend 'gcs'")
                .hasMessageContaining("valem-persistence-gcs");
    }
}
