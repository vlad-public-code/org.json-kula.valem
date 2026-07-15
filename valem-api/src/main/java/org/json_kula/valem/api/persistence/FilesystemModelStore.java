package org.json_kula.valem.api.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * @deprecated Moved to {@code valem-persistence-filesystem} module as
 *             {@code org.json_kula.valem.persistence.filesystem.FilesystemModelStore}.
 *             This copy is retained for backward compatibility only.
 */
@Deprecated
public final class FilesystemModelStore implements ModelStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemModelStore.class);
    private static final String SPEC_FILE     = "spec.json";
    private static final String SNAPSHOT_FILE = "snapshot.json";

    private final Path         root;
    private final ObjectMapper mapper;

    public FilesystemModelStore(Path root, ObjectMapper mapper) {
        this.root   = root;
        this.mapper = mapper;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create persistence directory: " + root, e);
        }
        log.info("FilesystemModelStore initialised at {}", root.toAbsolutePath());
    }

    @Override
    public void saveSpec(String modelId, ModelSpec spec) throws IOException {
        Path dir = modelDir(modelId);
        Files.createDirectories(dir);
        writeAtomic(dir.resolve(SPEC_FILE), mapper.writeValueAsBytes(spec));
        log.debug("Saved spec for model '{}'", modelId);
    }

    @Override
    public void saveSnapshot(String modelId, Snapshot snap) throws IOException {
        Path dir = modelDir(modelId);
        Files.createDirectories(dir);
        writeAtomic(dir.resolve(SNAPSHOT_FILE), snapshotToBytes(snap));
        log.debug("Saved snapshot for model '{}'", modelId);
    }

    @Override
    public Optional<ModelSpec> loadSpec(String modelId) throws IOException {
        Path file = modelDir(modelId).resolve(SPEC_FILE);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(mapper.readValue(file.toFile(), ModelSpec.class));
    }

    @Override
    public Optional<Snapshot> loadSnapshot(String modelId) throws IOException {
        Path file = modelDir(modelId).resolve(SNAPSHOT_FILE);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(snapshotFromFile(file));
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

    @Override
    public void deleteState(String modelId) throws IOException {
        Path dir = modelDir(modelId);
        if (!Files.exists(dir)) return;
        Files.deleteIfExists(dir.resolve(SNAPSHOT_FILE));
        log.debug("Deleted persisted state for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }

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
                ? mapper.convertValue(root.get("metaCache"),    mapType) : Map.of();
        return new Snapshot(modelId, modelVersion, baseDoc, derived, meta);
    }
}
