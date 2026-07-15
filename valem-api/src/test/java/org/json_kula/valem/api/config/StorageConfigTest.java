package org.json_kula.valem.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.persistence.CompositeModelStore;
import org.json_kula.valem.persistence.ModelStore;
import org.json_kula.valem.persistence.memory.InMemoryBlobStore;
import org.json_kula.valem.persistence.memory.InMemoryModelStore;
import org.json_kula.valem.persistence.mongo.MongoBlobStore;
import org.json_kula.valem.persistence.mongo.MongoStateStore;
import org.json_kula.valem.persistence.postgres.PostgresSpecStore;
import org.json_kula.valem.persistence.postgres.PostgresStateStore;
import org.json_kula.valem.persistence.s3.S3BlobStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link StorageConfig} per-concern backend resolution (F-T4). These exercise bean
 * <em>wiring</em> only — store constructors are lazy (no driver connection), so no live Postgres /
 * Mongo / S3 is required.
 */
class StorageConfigTest {

    @SuppressWarnings("unchecked")
    private static StorageConfig config(MockEnvironment env) {
        // No DataSource bean in context → StorageConfig builds a DriverManagerDataSource from props.
        ObjectProvider<DataSource> noDataSource = mock(ObjectProvider.class);
        return new StorageConfig(env, new ObjectMapper(), noDataSource);
    }

    @Test
    void defaults_to_in_memory_model_and_blob_store() {
        StorageConfig c = config(new MockEnvironment());
        assertThat(c.modelStore()).isInstanceOf(InMemoryModelStore.class);
        assertThat(c.modelStore().isEnabled()).isFalse();
        assertThat(c.blobStore()).isInstanceOf(InMemoryBlobStore.class);
    }

    @Test
    void legacy_storage_type_puts_spec_and_state_on_the_same_backend() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("valem.storage.type", "postgres")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/test");

        ModelStore store = config(env).modelStore();
        assertThat(store).isInstanceOf(CompositeModelStore.class);
        CompositeModelStore composite = (CompositeModelStore) store;
        assertThat(composite.specStore()).isInstanceOf(PostgresSpecStore.class);
        assertThat(composite.stateStore()).isInstanceOf(PostgresStateStore.class);
    }

    @Test
    void per_concern_types_mix_spec_and_state_backends() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("valem.storage.spec-type", "postgres")
                .withProperty("valem.storage.state-type", "mongodb")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/test");

        ModelStore store = config(env).modelStore();
        assertThat(store).isInstanceOf(CompositeModelStore.class);
        CompositeModelStore composite = (CompositeModelStore) store;
        assertThat(composite.specStore()).isInstanceOf(PostgresSpecStore.class);
        assertThat(composite.stateStore()).isInstanceOf(MongoStateStore.class);
    }

    @Test
    void blob_type_is_independently_selectable() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("valem.storage.type", "mongodb")
                .withProperty("valem.storage.blob-type", "s3")
                .withProperty("valem.storage.s3.bucket", "my-bucket");

        assertThat(config(env).blobStore()).isInstanceOf(S3BlobStore.class);
    }

    @Test
    void blob_defaults_to_the_db_backend_when_storage_type_is_a_db() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("valem.storage.type", "mongodb");
        assertThat(config(env).blobStore()).isInstanceOf(MongoBlobStore.class);
    }

    @Test
    void postgresql_and_mongo_aliases_are_accepted() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("valem.storage.spec-type", "postgresql")
                .withProperty("valem.storage.state-type", "mongo")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/test");

        CompositeModelStore composite = (CompositeModelStore) config(env).modelStore();
        assertThat(composite.specStore()).isInstanceOf(PostgresSpecStore.class);
        assertThat(composite.stateStore()).isInstanceOf(MongoStateStore.class);
    }
}
