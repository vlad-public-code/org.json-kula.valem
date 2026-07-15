package org.json_kula.valem.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.persistence.CompositeModelStore;
import org.json_kula.valem.persistence.ModelStore;
import org.json_kula.valem.persistence.SpecStore;
import org.json_kula.valem.persistence.StateStore;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.persistence.audit.DisabledAuditStore;
import org.json_kula.valem.persistence.spi.Concern;
import org.json_kula.valem.persistence.spi.PersistenceProvider;
import org.json_kula.valem.persistence.spi.ProviderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeSet;

/**
 * Unified storage configuration with <b>per-concern backend selection</b> (F-T4), wired through the
 * {@link PersistenceProvider} SPI.
 *
 * <p>Each of the four storage concerns — model <b>spec</b>, runtime <b>state</b>, binary
 * <b>blobs</b>, and the durable <b>audit</b> trail — picks its backend independently, so e.g. specs
 * can live in Postgres, state in Redis, and blobs in S3. The selectors, in precedence order:
 * <ul>
 *   <li><b>spec:</b> {@code valem.storage.spec-type} → {@code valem.storage.type} →
 *       {@code filesystem} if {@code valem.persistence-dir} is set → {@code memory}.</li>
 *   <li><b>state:</b> {@code valem.storage.state-type} → {@code valem.storage.type} →
 *       {@code filesystem} if {@code valem.persistence-dir} is set → {@code memory}.</li>
 *   <li><b>blob:</b> {@code valem.storage.blob-type} → {@code s3} if
 *       {@code valem.storage.blob=s3} → legacy {@code valem.blob-store}
 *       (memory|filesystem) → the DB backend if {@code valem.storage.type} is postgres/mongodb
 *       → {@code memory}.</li>
 *   <li><b>audit:</b> {@code valem.storage.audit-type} → follows the state backend when it is
 *       filesystem/postgres/mongodb → otherwise {@code none}.</li>
 * </ul>
 *
 * <p>Backends are discovered on the classpath via {@link ServiceLoader}: each adapter jar
 * ({@code valem-persistence-postgres}, {@code -mongo}, {@code -s3}, …) contributes a
 * {@link PersistenceProvider}. {@code valem-api} therefore no longer compile-depends on every
 * adapter — a backend is present iff its jar is on the classpath, and selecting a backend with no
 * matching provider fails with a clear "add the jar" message rather than an obscure wiring error.
 *
 * <p>When spec and state resolve to the same backend, a single combined {@link ModelStore} backs
 * both (memory/filesystem) or a {@link CompositeModelStore} sharing one client does (postgres/mongo/
 * redis); when they differ, a {@link CompositeModelStore} wires the two halves across providers.
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    private final Environment                  env;
    private final ObjectMapper                 mapper;
    private final ObjectProvider<DataSource>   dataSourceProvider;

    /** All discovered providers, indexed by {@link PersistenceProvider#type()}; closed on shutdown. */
    private final Map<String, PersistenceProvider> providers = new LinkedHashMap<>();

    // Built lazily on first use so the (nullable) external DataSource is resolved once.
    private ProviderContext ctx;

    public StorageConfig(Environment env, ObjectMapper mapper,
                         ObjectProvider<DataSource> dataSourceProvider) {
        this.env                = env;
        this.mapper             = mapper;
        this.dataSourceProvider = dataSourceProvider;
        for (PersistenceProvider p : ServiceLoader.load(PersistenceProvider.class)) {
            PersistenceProvider prior = providers.putIfAbsent(p.type(), p);
            if (prior != null) {
                log.warn("Multiple persistence providers registered for type '{}'; keeping {} and "
                        + "ignoring {}", p.type(), prior.getClass().getName(), p.getClass().getName());
            }
        }
    }

    // ── Beans ────────────────────────────────────────────────────────────────────

    @Bean
    public ModelStore modelStore() {
        String specType  = resolveSpecType();
        String stateType = resolveStateType();

        if (specType.equals(stateType)) {
            PersistenceProvider p = require(specType, Concern.SPEC);
            SpecStore  spec  = specStore(p, Concern.SPEC);
            StateStore state = stateStore(p, Concern.STATE);
            // memory/filesystem return one instance that is itself a ModelStore for both concerns.
            if (spec == state && spec instanceof ModelStore combined) {
                log.info("Storage: spec+state backend '{}' (combined)", specType);
                return combined;
            }
            // postgres/mongo/redis: two stores sharing one client.
            log.info("Storage: spec+state backend '{}' (composite, shared client)", specType);
            return new CompositeModelStore(spec, state);
        }
        ModelStore composite = new CompositeModelStore(
                specStore(require(specType, Concern.SPEC), Concern.SPEC),
                stateStore(require(stateType, Concern.STATE), Concern.STATE));
        log.info("Storage: spec backend '{}', state backend '{}' (composite)", specType, stateType);
        return composite;
    }

    @Bean
    public BlobStore blobStore() {
        String blobType = resolveBlobType();
        log.info("Storage: blob backend '{}'", blobType);
        return require(blobType, Concern.BLOB).blobStore(ctx());
    }

    /**
     * Durable, append-only audit trail (independently selectable). Unset defaults to {@code filesystem}
     * when state is filesystem-backed (co-located under {@code persistence-dir}), otherwise {@code none}
     * (the runtime's in-memory explainability still works; nothing is retained beyond it). Explicit
     * values: {@code none}, {@code memory} (retained, non-durable), {@code filesystem}, {@code postgres},
     * {@code mongodb}.
     */
    @Bean
    public AuditStore auditStore() {
        String auditType = resolveAuditType();
        log.info("Storage: audit backend '{}'", auditType);
        if (auditType.equals("none")) {
            return new DisabledAuditStore();
        }
        return require(auditType, Concern.AUDIT).auditStore(ctx());
    }

    /** Releases every discovered provider's shared clients (JDBC pools, driver clients) on shutdown. */
    @PreDestroy
    public void closeProviders() {
        for (PersistenceProvider p : providers.values()) {
            try {
                p.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close persistence provider '{}': {}", p.type(), e.toString());
            }
        }
    }

    // ── Provider resolution ───────────────────────────────────────────────────────

    private PersistenceProvider require(String type, Concern concern) {
        PersistenceProvider p = providers.get(type);
        if (p == null) {
            throw new IllegalStateException(
                    concern.name().toLowerCase() + " backend '" + type + "' was selected but no "
                    + "persistence provider for it is on the classpath. Add the "
                    + "valem-persistence-" + artifactSuffix(type) + " adapter jar. "
                    + "Available backends: " + new TreeSet<>(providers.keySet()));
        }
        return p;
    }

    private SpecStore specStore(PersistenceProvider p, Concern concern) {
        return p.specStore(ctx());
    }

    private StateStore stateStore(PersistenceProvider p, Concern concern) {
        return p.stateStore(ctx());
    }

    /** Maps a backend type to its adapter artifact suffix (only {@code mongodb} → {@code mongo} differs). */
    private static String artifactSuffix(String type) {
        return "mongodb".equals(type) ? "mongo" : type;
    }

    private ProviderContext ctx() {
        if (ctx == null) {
            ctx = new ProviderContext(env::getProperty, mapper, dataSourceProvider.getIfAvailable());
        }
        return ctx;
    }

    // ── Type resolution ───────────────────────────────────────────────────────────

    private String resolveSpecType()  { return resolveModelType("valem.storage.spec-type"); }
    private String resolveStateType() { return resolveModelType("valem.storage.state-type"); }

    private String resolveModelType(String perConcernKey) {
        String t = env.getProperty(perConcernKey);
        if (isBlank(t)) t = env.getProperty("valem.storage.type");
        if (isBlank(t)) t = has("valem.persistence-dir") ? "filesystem" : "memory";
        return normalize(t);
    }

    private String resolveAuditType() {
        String t = env.getProperty("valem.storage.audit-type");
        if (isBlank(t)) {
            // Default: follow the state backend when it is one with a durable audit adapter
            // (filesystem/postgres/mongodb); otherwise retention is off.
            String state = resolveStateType();
            t = switch (state) {
                case "filesystem", "postgres", "mongodb" -> state;
                default -> "none";
            };
        }
        return normalize(t.strip().toLowerCase());
    }

    private String resolveBlobType() {
        String t = env.getProperty("valem.storage.blob-type");
        if (isBlank(t)) {
            if ("s3".equalsIgnoreCase(env.getProperty("valem.storage.blob", ""))) {
                t = "s3";
            } else if (has("valem.blob-store")) {
                t = env.getProperty("valem.blob-store"); // memory | filesystem
            } else {
                String dbType = normalize(env.getProperty("valem.storage.type"));
                t = (dbType.equals("postgres") || dbType.equals("mongodb")) ? dbType : "memory";
            }
        }
        return normalize(t);
    }

    private static String normalize(String type) {
        if (isBlank(type)) return "memory";
        String t = type.strip().toLowerCase();
        return switch (t) {
            case "postgresql" -> "postgres";
            case "mongo"      -> "mongodb";
            default           -> t;
        };
    }

    // ── Property helpers ──────────────────────────────────────────────────────────

    private boolean has(String key) { return !isBlank(env.getProperty(key)); }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
