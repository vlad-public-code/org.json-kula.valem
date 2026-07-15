package org.json_kula.valem.persistence.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.persistence.SpecStore;
import org.json_kula.valem.persistence.StateStore;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.persistence.spi.Concern;
import org.json_kula.valem.persistence.spi.PersistenceProvider;
import org.json_kula.valem.persistence.spi.ProviderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.EnumSet;
import java.util.Set;

/**
 * PostgreSQL-backed {@link PersistenceProvider}. Serves all four concerns off one shared
 * {@link JdbcTemplate}.
 *
 * <p>The JDBC {@link DataSource} is resolved lazily: if the host supplies one via
 * {@link ProviderContext#dataSource()} the provider uses it (and does not own its lifecycle);
 * otherwise it synthesizes a pooled {@link HikariDataSource} from {@code spring.datasource.*}
 * properties and releases it in {@link #close()}.
 */
public final class PostgresPersistenceProvider implements PersistenceProvider {

    private static final Logger log = LoggerFactory.getLogger(PostgresPersistenceProvider.class);

    private JdbcTemplate jdbc;
    // Non-null only when this provider synthesized its own pool; closed in close().
    private HikariDataSource ownedDataSource;

    @Override
    public String type() {
        return "postgres";
    }

    @Override
    public Set<Concern> concerns() {
        return EnumSet.of(Concern.SPEC, Concern.STATE, Concern.BLOB, Concern.AUDIT);
    }

    @Override
    public SpecStore specStore(ProviderContext ctx) {
        return new PostgresSpecStore(jdbc(ctx), ctx.mapper());
    }

    @Override
    public StateStore stateStore(ProviderContext ctx) {
        return new PostgresStateStore(jdbc(ctx), ctx.mapper(), compactionThreshold(ctx));
    }

    @Override
    public BlobStore blobStore(ProviderContext ctx) {
        return new PostgresBlobStore(jdbc(ctx));
    }

    @Override
    public AuditStore auditStore(ProviderContext ctx) {
        return new PostgresAuditStore(jdbc(ctx), ctx.mapper());
    }

    private synchronized JdbcTemplate jdbc(ProviderContext ctx) {
        if (jdbc == null) {
            DataSource ds = ctx.dataSource();
            if (ds == null) {
                ds = synthesizePool(ctx);
            }
            jdbc = new JdbcTemplate(ds);
        }
        return jdbc;
    }

    private HikariDataSource synthesizePool(ProviderContext ctx) {
        // Synthesize a *pooled* DataSource. A pool-less DriverManagerDataSource would open a fresh
        // connection + auth handshake per JDBC call — and state writes run inside the model lock, so
        // that latency would serialise mutations. Hikari amortises it.
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ctx.require("spring.datasource.url"));
        String user = ctx.get("spring.datasource.username", "");
        String pass = ctx.get("spring.datasource.password", "");
        if (!user.isBlank()) cfg.setUsername(user);
        if (!pass.isBlank()) cfg.setPassword(pass);
        String driver = ctx.get("spring.datasource.driver-class-name");
        if (driver != null && !driver.isBlank()) cfg.setDriverClassName(driver);
        cfg.setPoolName("valem-jdbc");
        cfg.setMaximumPoolSize(ctx.intProp("valem.storage.jdbc.pool-size", 8));
        // Lazy: do not open a connection at construction (failTimeout -1) and keep no pre-warmed idle
        // connections, so building a store never blocks on or fails from an unreachable DB —
        // connections are created on first use and then pooled/reused.
        cfg.setMinimumIdle(0);
        cfg.setInitializationFailTimeout(-1);
        cfg.setConnectionTimeout(30_000);
        HikariDataSource hikari = new HikariDataSource(cfg);
        this.ownedDataSource = hikari;
        log.info("Storage: synthesized pooled HikariDataSource (maxPoolSize={})",
                cfg.getMaximumPoolSize());
        return hikari;
    }

    private static int compactionThreshold(ProviderContext ctx) {
        return ctx.intProp("valem.storage.compaction-threshold", 100);
    }

    @Override
    public synchronized void close() {
        if (ownedDataSource != null && !ownedDataSource.isClosed()) {
            ownedDataSource.close();
        }
    }
}
