package org.json_kula.valem.persistence.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.persistence.SpecStore;
import org.json_kula.valem.persistence.StateStore;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.persistence.spi.Concern;
import org.json_kula.valem.persistence.spi.PersistenceProvider;
import org.json_kula.valem.persistence.spi.ProviderContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * MongoDB-backed {@link PersistenceProvider}. Serves all four concerns (blobs via GridFS) off one
 * shared {@link MongoClient}, opened lazily from {@code spring.data.mongodb.uri}/{@code .database}
 * and closed in {@link #close()}.
 */
public final class MongoPersistenceProvider implements PersistenceProvider {

    private MongoClient client;
    private MongoDatabase db;

    @Override
    public String type() {
        return "mongodb";
    }

    @Override
    public Set<Concern> concerns() {
        return EnumSet.of(Concern.SPEC, Concern.STATE, Concern.BLOB, Concern.AUDIT);
    }

    @Override
    public SpecStore specStore(ProviderContext ctx) {
        return new MongoSpecStore(db(ctx), ctx.mapper());
    }

    @Override
    public StateStore stateStore(ProviderContext ctx) {
        return new MongoStateStore(db(ctx), ctx.mapper(),
                ctx.intProp("valem.storage.compaction-threshold", 100));
    }

    @Override
    public BlobStore blobStore(ProviderContext ctx) {
        return new MongoBlobStore(db(ctx));
    }

    @Override
    public AuditStore auditStore(ProviderContext ctx) {
        return new MongoAuditStore(db(ctx), ctx.mapper());
    }

    private synchronized MongoDatabase db(ProviderContext ctx) {
        if (db == null) {
            String uri    = ctx.get("spring.data.mongodb.uri", "mongodb://localhost:27017/valem");
            String dbName = ctx.get("spring.data.mongodb.database", "valem");
            client = MongoClients.create(uri);
            db = client.getDatabase(dbName);
        }
        return db;
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
        }
    }
}
