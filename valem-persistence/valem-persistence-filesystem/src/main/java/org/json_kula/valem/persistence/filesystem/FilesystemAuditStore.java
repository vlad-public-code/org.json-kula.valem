package org.json_kula.valem.persistence.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.persistence.audit.AuditHashing;
import org.json_kula.valem.persistence.audit.AuditQuery;
import org.json_kula.valem.persistence.audit.AuditRecord;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.persistence.audit.AuditVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Durable, append-only {@link AuditStore} backed by the local filesystem.
 *
 * <p>Layout: {@code {root}/{modelId}/audit.jsonl} — one JSON {@link AuditRecord} per line. The file
 * is <b>never compacted or truncated</b>: it is the tamper-evident trail, so it grows monotonically
 * with committed cycles. The per-model {@link AuditRecord#sequence} equals the line index at append
 * time (0-based).
 *
 * <p>Appends use {@link StandardOpenOption#APPEND}, which is atomic for small writes on POSIX/NTFS;
 * the caller ({@code ModelService}) serialises appends per model under the runtime lock, so line
 * order matches commit order. Queries stream the file, filter, sort most-recent-first, and limit.
 */
public final class FilesystemAuditStore implements AuditStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemAuditStore.class);
    private static final String AUDIT_FILE = "audit.jsonl";

    private final Path         root;
    private final ObjectMapper mapper;

    public FilesystemAuditStore(Path root, ObjectMapper mapper) {
        this.root   = root;
        this.mapper = mapper;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create audit directory: " + root, e);
        }
        log.info("FilesystemAuditStore initialised at {}", root.toAbsolutePath());
    }

    @Override
    public AuditRecord append(AuditRecord record) throws IOException {
        Path file = auditFile(record.modelId());
        Files.createDirectories(file.getParent());
        long seq = existingCount(file);
        String prevHash = seq == 0 ? AuditHashing.GENESIS : lastHash(file);
        AuditRecord stamped = AuditHashing.chain(record.withSequence(seq), prevHash);
        String line = mapper.writeValueAsString(stamped) + "\n";
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return stamped;
    }

    @Override
    public AuditVerification verify(String modelId) throws IOException {
        Path file = auditFile(modelId);
        if (!Files.exists(file)) return AuditVerification.valid(0);
        List<AuditRecord> records = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                records.add(mapper.readValue(line, AuditRecord.class));
            } catch (IOException e) {
                return AuditVerification.broken(records.size(), records.size(),
                        "unreadable audit line at position " + records.size() + ": " + e.getMessage());
            }
        }
        return AuditHashing.verifyChain(records); // file order is append/sequence order
    }

    /** Reads the {@code hash} of the last non-blank record without parsing the whole file. */
    private String lastHash(Path file) throws IOException {
        String last = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) last = line;
        }
        if (last == null) return AuditHashing.GENESIS;
        String h = mapper.readValue(last, AuditRecord.class).hash();
        return h == null ? AuditHashing.GENESIS : h;
    }

    @Override
    public List<AuditRecord> query(AuditQuery query) throws IOException {
        Path file = auditFile(query.modelId());
        if (!Files.exists(file)) return List.of();
        List<AuditRecord> matches = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            AuditRecord rec;
            try {
                rec = mapper.readValue(line, AuditRecord.class);
            } catch (IOException e) {
                log.warn("Skipping corrupt audit line for model '{}': {}", query.modelId(), e.toString());
                continue;
            }
            if (query.inWindow(rec.instant()) && rec.touchesPath(query.pathPrefix())) {
                matches.add(rec);
            }
        }
        matches.sort(Comparator.comparingLong(AuditRecord::sequence).reversed());
        int limit = query.effectiveLimit();
        return matches.size() > limit ? List.copyOf(matches.subList(0, limit)) : List.copyOf(matches);
    }

    @Override
    public long count(String modelId) throws IOException {
        return existingCount(auditFile(modelId));
    }

    @Override
    public void deleteAudit(String modelId) throws IOException {
        Files.deleteIfExists(auditFile(modelId));
    }

    @Override
    public boolean isEnabled() { return true; }

    private long existingCount(Path file) throws IOException {
        if (!Files.exists(file)) return 0;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).count();
        }
    }

    private Path auditFile(String modelId) {
        if (modelId == null || modelId.isBlank())
            throw new IllegalArgumentException("Model id must not be blank");
        Path dir = root.resolve(modelId).normalize();
        if (!dir.startsWith(root.normalize()))
            throw new IllegalArgumentException("Invalid model id: " + modelId);
        return dir.resolve(AUDIT_FILE);
    }
}
