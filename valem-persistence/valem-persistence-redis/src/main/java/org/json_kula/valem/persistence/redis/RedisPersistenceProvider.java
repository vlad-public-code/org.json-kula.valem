package org.json_kula.valem.persistence.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.json_kula.valem.persistence.SpecStore;
import org.json_kula.valem.persistence.StateStore;
import org.json_kula.valem.persistence.spi.Concern;
import org.json_kula.valem.persistence.spi.PersistenceProvider;
import org.json_kula.valem.persistence.spi.ProviderContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * Redis-backed {@link PersistenceProvider}. Serves {@link Concern#SPEC} and {@link Concern#STATE}
 * only (Redis has no blob or audit store). Owns one shared Lettuce connection, opened lazily from
 * {@code spring.data.redis.url} and closed in {@link #close()}.
 */
public final class RedisPersistenceProvider implements PersistenceProvider {

    private RedisClient client;
    private StatefulRedisConnection<String, String> conn;

    @Override
    public String type() {
        return "redis";
    }

    @Override
    public Set<Concern> concerns() {
        return EnumSet.of(Concern.SPEC, Concern.STATE);
    }

    @Override
    public SpecStore specStore(ProviderContext ctx) {
        return new RedisSpecStore(conn(ctx), ctx.mapper());
    }

    @Override
    public StateStore stateStore(ProviderContext ctx) {
        return new RedisStateStore(conn(ctx), ctx.mapper(),
                ctx.intProp("valem.storage.compaction-threshold", 100));
    }

    private synchronized StatefulRedisConnection<String, String> conn(ProviderContext ctx) {
        if (conn == null) {
            String url = ctx.get("spring.data.redis.url", "redis://localhost:6379");
            client = RedisClient.create(url);
            conn = client.connect();
        }
        return conn;
    }

    @Override
    public synchronized void close() {
        if (conn != null) {
            conn.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
