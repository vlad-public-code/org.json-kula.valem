package org.json_kula.valem.persistence.filesystem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.persistence.ModelStore;
import org.json_kula.valem.persistence.MutationLogReplay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Durable {@link ModelStore} backed by the local filesystem.
 *
 * <p>Directory layout:
 * <pre>
 *   {root}/{modelId}/
 *     spec.json       — ModelSpec
 *     snapshot.json   — baseline state snapshot (baseDoc + derivedCache + metaCache)
 *     mutations.jsonl — incremental mutation log (one RFC 6902 patch record per line)
 * </pre>
 *
 * <p>Every successful mutation appends one JSON line to {@code mutations.jsonl}.
 * When the line count exceeds {@code compactionThreshold}, the log is compacted into a
 * new baseline {@code snapshot.json} and the log is truncated.
 *
 * <p>On load, the baseline is read and all pending log records are replayed to reconstruct
 * current state. Load time is therefore O(N) where N ≤ compactionThreshold.
 *
 * <p>All writes to {@code spec.json} and {@code snapshot.json} are atomic (temp + rename).
 */
public final class FilesystemModelStore implements ModelStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemModelStore.class);
    private static final String SPEC_FILE      = "spec.json";
    private static final String SNAPSHOT_FILE  = "snapshot.json";
    private static final String MUTATIONS_FILE = "mutations.jsonl";

    private final Path         root;
    private final ObjectMapper mapper;
    private final int          compactionThreshold;

    // In-memory mutation-log line counts per model (audit CPU-8): avoids re-reading the whole log
    // to count lines on every append. Seeded by one real count on first append per process, then
    // incremented; reset on snapshot/compaction. Per-model appends are serialised by the caller's
    // model lock, so the get/put pair below is race-free for a given model.
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lineCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    public FilesystemModelStore(Path root, ObjectMapper mapper) {
        this(root, mapper, 100);
    }

    public FilesystemModelStore(Path root, ObjectMapper mapper, int compactionThreshold) {
        this.root                = root;
        this.mapper              = mapper;
        this.compactionThreshold = compactionThreshold;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create persistence directory: " + root, e);
        }
        log.info("FilesystemModelStore initialised at {}", root.toAbsolutePath());
    }

    // ── SpecStore ─────────────────────────────────────────────────────────────

    @Override
    public void saveSpec(String modelId, ModelSpec spec) throws IOException {
        Path dir = modelDir(modelId);
        Files.createDirectories(dir);
        writeAtomic(dir.resolve(SPEC_FILE), mapper.writeValueAsBytes(spec));
        log.debug("Saved spec for model '{}'", modelId);
    }

    @Override
    public Optional<ModelSpec> loadSpec(String modelId) throws IOException {
        Path file = modelDir(modelId).resolve(SPEC_FILE);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(mapper.readValue(file.toFile(), ModelSpec.class));
    }

    @Override
    public List<String> modelIds() throws IOException {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve(SPEC_FILE)))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    @Override
    public void delete(String modelId) throws IOException {
        deleteState(modelId);
        Path dir = modelDir(modelId);
        if (!Files.exists(dir)) return;
        Files.deleteIfExists(dir.resolve(SPEC_FILE));
        Files.deleteIfExists(dir);
        log.debug("Deleted persisted data for model '{}'", modelId);
    }

    // ── StateStore ────────────────────────────────────────────────────────────

    @Override
    public void saveSnapshot(String modelId, Snapshot snap) throws IOException {
        Path dir = modelDir(modelId);
        Files.createDirectories(dir);
        writeAtomic(dir.resolve(SNAPSHOT_FILE), snapshotToBytes(snap));
        // Clear the mutation log — new baseline supersedes all pending patches
        Path mutFile = dir.resolve(MUTATIONS_FILE);
        if (Files.exists(mutFile)) {
            Files.write(mutFile, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
        }
        lineCounts.put(modelId, 0L);   // log truncated
        log.debug("Saved snapshot for model '{}'", modelId);
    }

    @Override
    public void applyMutationPatch(String modelId, ArrayNode patch, Instant mutatedAt)
            throws IOException {
        Path dir = modelDir(modelId);
        Files.createDirectories(dir);

        // Append one JSONL record
        ObjectNode record = mapper.createObjectNode();
        record.put("ts", mutatedAt.toString());
        record.set("patch", patch);
        String line = mapper.writeValueAsString(record) + "\n";
        Path mutFile = dir.resolve(MUTATIONS_FILE);
        Files.writeString(mutFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        // Track the line count incrementally instead of re-reading the whole log every append.
        Long known = lineCounts.get(modelId);
        long lineCount = (known == null) ? countLines(mutFile) : known + 1;
        lineCounts.put(modelId, lineCount);

        if (lineCount > compactionThreshold) {
            long remaining = compact(modelId, dir, mutFile);
            if (remaining >= 0) lineCounts.put(modelId, remaining);
        }
    }

    @Override
    public Optional<Snapshot> loadSnapshot(String modelId) throws IOException {
        Path snapshotFile = modelDir(modelId).resolve(SNAPSHOT_FILE);
        if (!Files.exists(snapshotFile)) return Optional.empty();

        Snapshot baseline = snapshotFromFile(snapshotFile);
        Path mutFile = modelDir(modelId).resolve(MUTATIONS_FILE);
        if (!Files.exists(mutFile) || Files.size(mutFile) == 0) {
            return Optional.of(baseline);
        }

        List<String> lines = Files.readAllLines(mutFile, StandardCharsets.UTF_8);
        return Optional.of(replayLines(baseline, lines));
    }

    /**
     * Replays the given mutation-log lines onto a copy of {@code baseline}'s base document.
     * Create-as-you-go (F-T5): each persisted op reconstructs nested paths exactly like the live
     * write path, so a {@code add /items/0/price} is never silently dropped for a missing parent.
     */
    private Snapshot replayLines(Snapshot baseline, List<String> lines) throws IOException {
        ObjectNode baseDoc = baseline.baseDoc().deepCopy();
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonNode record = mapper.readTree(line);
            JsonNode patchNode = record.get("patch");
            if (patchNode == null || !patchNode.isArray()) continue;
            baseDoc = MutationLogReplay.apply(baseDoc, patchNode);
        }
        return new Snapshot(
                baseline.modelId(), baseline.modelVersion(),
                baseDoc, baseline.derivedCache(), baseline.metaCache());
    }

    @Override
    public void deleteState(String modelId) throws IOException {
        Path dir = modelDir(modelId);
        lineCounts.remove(modelId);
        if (!Files.exists(dir)) return;
        Files.deleteIfExists(dir.resolve(SNAPSHOT_FILE));
        Files.deleteIfExists(dir.resolve(MUTATIONS_FILE));
        log.debug("Deleted persisted state for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }

    // ── Compaction ────────────────────────────────────────────────────────────

    /**
     * Merges the log into the baseline and truncates it, pinned to the line count read at the start
     * (F-T7): exactly the {@code boundary} lines that were merged are removed, and any line appended
     * beyond the boundary is preserved (instead of the previous blanket truncate that would discard
     * a line written after the merge read it).
     */
    private long compact(String modelId, Path dir, Path mutFile) {
        try {
            Path snapshotFile = dir.resolve(SNAPSHOT_FILE);
            if (!Files.exists(snapshotFile)) return -1;
            Snapshot baseline = snapshotFromFile(snapshotFile);

            List<String> boundaryLines = Files.readAllLines(mutFile, StandardCharsets.UTF_8);
            int boundary = boundaryLines.size();
            Snapshot merged = replayLines(baseline, boundaryLines);
            writeAtomic(snapshotFile, snapshotToBytes(merged));

            // Keep only entries appended past the merged boundary (normally none under the lock).
            List<String> all = Files.readAllLines(mutFile, StandardCharsets.UTF_8);
            List<String> remaining = boundary < all.size()
                    ? all.subList(boundary, all.size()) : List.of();
            if (remaining.isEmpty()) {
                Files.write(mutFile, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.writeString(mutFile, String.join("\n", remaining) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            }
            log.debug("Compacted mutation log for model '{}' up to offset {}", modelId, boundary);
            return remaining.size();
        } catch (IOException e) {
            log.warn("Compaction deferred for model '{}': {}", modelId, e.getMessage());
            return -1; // unchanged — caller keeps the existing counter
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path modelDir(String modelId) {
        if (modelId == null || modelId.isBlank())
            throw new IllegalArgumentException("Model id must not be blank");
        Path resolved = root.resolve(modelId).normalize();
        if (!resolved.startsWith(root.normalize()))
            throw new IllegalArgumentException("Invalid model id: " + modelId);
        return resolved;
    }

    private static void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static long countLines(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).count();
        }
    }

    private byte[] snapshotToBytes(Snapshot snap) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("modelId",      snap.modelId());
        node.put("modelVersion", snap.modelVersion());
        node.set("baseDoc",      snap.baseDoc());
        node.set("derivedCache", mapper.valueToTree(snap.derivedCache()));
        node.set("metaCache",    mapper.valueToTree(snap.metaCache()));
        return mapper.writeValueAsBytes(node);
    }

    private Snapshot snapshotFromFile(Path file) throws IOException {
        JsonNode root = mapper.readTree(file.toFile());
        String modelId      = root.path("modelId").asText("");
        String modelVersion = root.path("modelVersion").asText("1.0.0");
        ObjectNode baseDoc  = root.has("baseDoc") && root.get("baseDoc").isObject()
                ? (ObjectNode) root.get("baseDoc")
                : mapper.createObjectNode();
        TypeReference<Map<String, JsonNode>> mapType = new TypeReference<>() {};
        Map<String, JsonNode> derived = root.has("derivedCache")
                ? mapper.convertValue(root.get("derivedCache"), mapType) : Map.of();
        Map<String, JsonNode> meta    = root.has("metaCache")
                ? mapper.convertValue(root.get("metaCache"), mapType) : Map.of();
        return new Snapshot(modelId, modelVersion, baseDoc, derived, meta);
    }
}
