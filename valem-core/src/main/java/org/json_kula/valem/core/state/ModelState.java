package org.json_kula.valem.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.core.model.EvaluationMode;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime state container for a single model instance.
 *
 * <p>Holds three layers:
 * <ol>
 *   <li><b>Base document</b> — the mutable JSON tree of writable field values.</li>
 *   <li><b>Derived cache</b> — computed values produced by derivation expressions.</li>
 *   <li><b>Meta cache</b> — per-field metadata produced by meta-derivation expressions.</li>
 * </ol>
 *
 * <p>Field access uses JsonPath expressions (e.g. {@code "$.order.items[0].qty"}).
 *
 * <p><b>Not thread-safe.</b> External synchronisation required for concurrent mutation.
 */
public final class ModelState {

    private final CompiledModel model;
    private final BlobStore blobStore;

    private ObjectNode baseDoc;
    private final Map<String, JsonNode> derivedCache = new LinkedHashMap<>();
    private final Map<String, JsonNode> metaCache    = new LinkedHashMap<>();

    // Paths mutated since the last clearDirty() call; used to schedule re-evaluation
    private final Set<String> dirtyPaths = new LinkedHashSet<>();

    // LAZY derivation paths whose cached value is out-of-date; cleared when read on demand
    private final Set<String> staleLazyPaths = new LinkedHashSet<>();

    // Non-null only while a transaction is open
    private Snapshot transactionSnapshot = null;
    // Snapshot of staleLazyPaths taken at beginTransaction(); restored on rollback
    private Set<String> txStaleLazySnapshot = null;

    public ModelState(CompiledModel model, BlobStore blobStore) {
        this.model     = model;
        this.blobStore = blobStore;
        this.baseDoc   = JsonNodeFactory.instance.objectNode();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Reads the effective value of a field: derived cache first, then base document.
     * Returns a {@link com.fasterxml.jackson.databind.node.MissingNode} when the path is absent.
     */
    public JsonNode getValue(String dotPath) {
        JsonNode derived = derivedCache.get(dotPath);
        if (derived != null) return derived;
        return baseDoc.at(PathConverter.toJsonPointer(dotPath));
    }

    /**
     * Returns {@code true} if {@code dotPath} resolves to a present, non-null node in the base
     * document. A missing node or an explicit JSON null both count as absent. Used to decide
     * whether a default-value rule should fill a field (fill-absent semantics).
     */
    public boolean existsInBase(String dotPath) {
        JsonNode node = baseDoc.at(PathConverter.toJsonPointer(dotPath));
        return !node.isMissingNode() && !node.isNull();
    }

    /** Returns the cached derived value for {@code path}, or {@code null} if not yet computed. */
    public JsonNode getDerived(String path) { return derivedCache.get(path); }

    /** Returns the cached meta value for {@code nodeKey} (e.g. {@code "order.total#minimum"}). */
    public JsonNode getMeta(String nodeKey) { return metaCache.get(nodeKey); }

    /** Returns an unmodifiable view of the full meta cache, keyed by {@code "$.path#property"}. */
    public Map<String, JsonNode> metaCache() { return Collections.unmodifiableMap(metaCache); }

    /** Returns the base document wrapped in a {@link TrackedJsonNode} for read-only traversal. */
    public TrackedJsonNode asRoot() { return TrackedJsonNode.ofRoot(baseDoc); }

    /** Returns the raw base document (read-only Jackson node, not a defensive copy). */
    public ObjectNode baseDoc() { return baseDoc; }

    /**
     * Returns a deep copy of the base document with all derived values spliced in.
     * Use this as the context when evaluating constraint expressions, which may
     * reference derived fields by their dot-notation path.
     */
    public ObjectNode mergedDocument() {
        ObjectNode merged = baseDoc.deepCopy();
        for (Map.Entry<String, JsonNode> entry : derivedCache.entrySet()) {
            // Length-aware splice: never grow an array to fit a derived index. A derived entry
            // whose array index is beyond the current array length (e.g. a stale
            // $.items[2].lineTotal after the items array shrank to 1) is skipped rather than
            // splicing a phantom element. This makes the merged view structurally correct without
            // relying on a separate "clear stale wildcard entries" pass.
            setDerivedInDoc(merged, PathConverter.toSegments(entry.getKey()), entry.getValue());
        }
        return merged;
    }

    // ── Write (base fields only) ───────────────────────────────────────────────

    /**
     * Sets a base field value and marks the path dirty.
     *
     * @throws IllegalArgumentException if {@code dotPath} is a derived (read-only) field
     */
    public void setValue(String dotPath, JsonNode value) {
        if (model.derivationFor(dotPath) != null) {
            throw new IllegalArgumentException(
                    "Field \"" + dotPath + "\" is derived (read-only); cannot set directly.");
        }
        setInDoc(baseDoc, PathConverter.toSegments(dotPath), value);

        dirtyPaths.add(dotPath);
    }

    /** Returns {@code true} if the base document contains a {@link BlobRef} with the given id. */
    public boolean containsBlob(String blobId) {
        return nodeContainsBlob(baseDoc, blobId);
    }

    private static boolean nodeContainsBlob(JsonNode node, String blobId) {
        if (BlobRef.isBlobRef(node)) return blobId.equals(node.get("$blobId").asText());
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                if (nodeContainsBlob(child, blobId)) return true;
            }
        }
        return false;
    }

    /** Collects every {@link BlobRef} id reachable from this state's base document into {@code out}. */
    public void collectBlobIds(java.util.Set<String> out) {
        collectBlobIds(baseDoc, out);
    }

    /**
     * The single authoritative {@link BlobRef} document walk (audit MEM-6): the blob garbage
     * collector's "referenced" set and any other "which blobs does this document use" query must go
     * through this so the definition of a reference cannot drift between call sites.
     */
    public static void collectBlobIds(JsonNode node, java.util.Set<String> out) {
        if (node == null) return;
        if (BlobRef.isBlobRef(node)) {
            JsonNode id = node.get("$blobId");
            if (id != null) out.add(id.asText());
            return;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) collectBlobIds(child, out);
        }
    }

    /** Stores a blob in the BlobStore and writes its {@link BlobRef} to the model state. */
    public BlobRef storeBlob(String dotPath, java.io.InputStream data, String mediaType)
            throws java.io.IOException {
        // Release the old blob if one exists at this path
        JsonNode current = getValue(dotPath);
        if (BlobRef.isBlobRef(current)) {
            blobStore.delete(BlobRef.fromJsonNode(current).blobId());
        }
        BlobRef ref = blobStore.store(data, mediaType);
        setValue(dotPath, ref.toJsonNode());
        return ref;
    }

    // ── Derived / meta cache ──────────────────────────────────────────────────

    /** Updates the derived value cache for a computed field. */
    public void setDerived(String path, JsonNode value) {
        derivedCache.put(path, value);
    }

    /** Updates the meta cache for a computed metadata property. */
    public void setMeta(String nodeKey, JsonNode value) {
        metaCache.put(nodeKey, value);
    }

    /**
     * Removes derived cache entries whose paths match the given wildcard pattern.
     * Used before re-evaluating a wildcard derivation to purge stale entries from
     * array elements that no longer exist (e.g. when items array shrinks to empty).
     */
    public void clearDerivedMatchingWildcard(String wildcardPath) {
        derivedCache.keySet().removeIf(k -> DirtyPropagator.matchesPattern(wildcardPath, k));
    }

    // ── Dirty tracking (Task #8) ───────────────────────────────────────────────

    /** Returns an unmodifiable view of all paths marked dirty since the last {@link #clearDirty()}. */
    public Set<String> dirtyPaths() { return Collections.unmodifiableSet(dirtyPaths); }

    /** Explicitly marks a path dirty (used by the dirty propagation step). */
    public void markDirty(String path) { dirtyPaths.add(path); }

    /** Clears the dirty set after re-evaluation has settled. */
    public void clearDirty() { dirtyPaths.clear(); }

    // ── Lazy-stale tracking ────────────────────────────────────────────────────

    /** Marks a LAZY derivation path as stale — its cached value is outdated. */
    public void markLazyStale(String path) { staleLazyPaths.add(path); }

    /** Returns {@code true} if the LAZY derivation at {@code path} has stale cached value. */
    public boolean isLazyStale(String path) { return staleLazyPaths.contains(path); }

    /** Clears the stale marker for {@code path} after it has been evaluated on demand. */
    public void clearLazyStale(String path) { staleLazyPaths.remove(path); }

    // ── Transaction support ────────────────────────────────────────────────────

    /**
     * Begins a transaction by capturing a snapshot of the current state.
     * Nested transactions are not supported; calling this while a transaction is open
     * replaces the existing checkpoint.
     */
    public void beginTransaction() {
        transactionSnapshot    = snapshot();
        txStaleLazySnapshot    = new LinkedHashSet<>(staleLazyPaths);
    }

    /**
     * Commits the current transaction, discarding the rollback snapshot.
     *
     * @throws IllegalStateException if no transaction is open
     */
    public void commit() {
        requireTransaction();
        transactionSnapshot = null;
        txStaleLazySnapshot = null;
    }

    /**
     * Rolls back to the pre-transaction snapshot and closes the transaction.
     *
     * @throws IllegalStateException if no transaction is open
     */
    public void rollback() {
        requireTransaction();
        restore(transactionSnapshot);
        staleLazyPaths.clear();
        staleLazyPaths.addAll(txStaleLazySnapshot);
        transactionSnapshot = null;
        txStaleLazySnapshot = null;
    }

    public boolean inTransaction() { return transactionSnapshot != null; }

    // ── Snapshot / restore (Task #7) ──────────────────────────────────────────

    /**
     * Returns a new {@link ModelState} backed by {@code newModel} but carrying forward
     * all current base, derived, and meta values. Use this after a spec evolution.
     */
    public ModelState withModel(CompiledModel newModel) {
        ModelState next = new ModelState(newModel, blobStore);
        next.baseDoc = baseDoc.deepCopy();
        // Only carry derived cache entries that are still derived in the new model;
        // removed derivations must not shadow direct writes to the now-writable path.
        for (Map.Entry<String, JsonNode> e : derivedCache.entrySet()) {
            if (newModel.derivationFor(e.getKey()) != null) {
                next.derivedCache.put(e.getKey(), e.getValue());
            }
        }
        next.metaCache.putAll(metaCache);
        return next;
    }

    /** Creates an immutable deep copy of the current state. */
    public Snapshot snapshot() {
        return new Snapshot(
                model.spec().id(),
                model.spec().version(),
                baseDoc.deepCopy(),
                Map.copyOf(derivedCache),
                Map.copyOf(metaCache));
    }

    /** Replaces all state with the contents of {@code snap} and clears the dirty set. */
    public void restore(Snapshot snap) {
        baseDoc = snap.baseDoc().deepCopy();
        derivedCache.clear();
        derivedCache.putAll(snap.derivedCache());
        metaCache.clear();
        metaCache.putAll(snap.metaCache());
        dirtyPaths.clear();
        // After restore the base doc may differ from when lazy values were computed;
        // mark all lazy derivations stale so they are re-evaluated on next read.
        staleLazyPaths.clear();
        model.spec().derivations().stream()
                .filter(d -> d.evaluation() == EvaluationMode.LAZY)
                .map(d -> d.path())
                .forEach(staleLazyPaths::add);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // ── Incremental-log replay primitives ───────────────────────────────────────

    /**
     * Sets {@code value} at the path described by {@code segments}, creating intermediate objects
     * and arrays as needed (a numeric segment creates/extends an array) — the same create-as-you-go
     * semantics as {@link #setValue}. Used to replay a persisted RFC 6902 {@code add}/{@code replace}
     * op against a baseline document that may not yet contain the nested path, so the incremental
     * mutation log reconstructs the same state the live mutation produced (F-T5).
     */
    public static void applyAddOrReplace(ObjectNode root, List<String> segments, JsonNode value) {
        setInDoc(root, segments, value);
    }

    /**
     * Removes the leaf at {@code segments} if it is present; a no-op when any ancestor or the leaf
     * itself is absent. Tolerant replay of a persisted RFC 6902 {@code remove} op (F-T5).
     */
    public static void applyRemove(ObjectNode root, List<String> segments) {
        removeInDoc(root, segments);
    }

    /**
     * Navigates {@code root} following {@code segments} and removes the leaf, tolerating an absent
     * path at any level. Does not create intermediate nodes.
     */
    static void removeInDoc(ObjectNode root, List<String> segments) {
        if (segments.isEmpty()) return;

        JsonNode current = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            String seg = segments.get(i);
            if (current.isObject()) {
                current = current.get(seg);
            } else if (current.isArray() && isNumeric(seg)) {
                long idx = readIndex(seg);
                ArrayNode arr = (ArrayNode) current;
                current = (idx >= 0 && idx < arr.size()) ? arr.get((int) idx) : null;
            } else {
                return; // cannot navigate further — nothing to remove
            }
            if (current == null || current.isMissingNode()) return;
        }

        String leaf = segments.getLast();
        if (current.isObject()) {
            ((ObjectNode) current).remove(leaf);
        } else if (current.isArray() && isNumeric(leaf)) {
            ArrayNode arr = (ArrayNode) current;
            long idx = readIndex(leaf);
            if (idx >= 0 && idx < arr.size()) arr.remove((int) idx);
        }
    }

    /**
     * Navigates {@code root} following {@code segments} and sets the leaf value.
     * Intermediate objects and arrays are created on demand.
     *
     * <p>Works iteratively to avoid issues with null-padded array slots: when a null
     * slot is encountered during navigation it is replaced in-place with an ObjectNode
     * or ArrayNode before recursing into it.
     */
    static void setInDoc(ObjectNode root, List<String> segments, JsonNode value) {
        if (segments.isEmpty()) return;

        JsonNode current = root;
        // Navigate to the parent of the leaf, creating intermediate nodes as needed
        for (int i = 0; i < segments.size() - 1; i++) {
            String seg     = segments.get(i);
            String nextSeg = segments.get(i + 1);

            if (current.isObject()) {
                ObjectNode obj   = (ObjectNode) current;
                JsonNode   child = obj.get(seg);
                if (child == null || child.isMissingNode() || child.isNull()) {
                    child = isNumeric(nextSeg)
                            ? JsonNodeFactory.instance.arrayNode()
                            : JsonNodeFactory.instance.objectNode();
                    obj.set(seg, child);
                }
                current = child;
            } else if (current.isArray()) {
                ArrayNode arr = (ArrayNode) current;
                int       idx = arrayIndex(seg);
                while (arr.size() <= idx) arr.addNull();
                JsonNode child = arr.get(idx);
                if (child == null || child.isNull() || child.isMissingNode()) {
                    child = isNumeric(nextSeg)
                            ? JsonNodeFactory.instance.arrayNode()
                            : JsonNodeFactory.instance.objectNode();
                    arr.set(idx, child);  // replace the null slot
                }
                current = child;
            }
        }

        // Set the leaf value on the parent we navigated to
        String leaf = segments.getLast();
        if (current.isObject()) {
            ((ObjectNode) current).set(leaf, value);
        } else if (current.isArray()) {
            ArrayNode arr = (ArrayNode) current;
            int       idx = arrayIndex(leaf);
            while (arr.size() <= idx) arr.addNull();
            arr.set(idx, value);
        }
    }

    /**
     * Splices a single derived {@code value} at {@code dotPath} into an externally-owned merged
     * document, using the same length-aware semantics as {@link #mergedDocument()} (an array index
     * at or beyond the current length is skipped, never grown).
     *
     * <p>Lets the derivation evaluator maintain <b>one</b> merged document incrementally across
     * topological levels — splicing each level's newly computed derivations in before the next
     * level reads it — instead of rebuilding the whole tree via {@link #mergedDocument()} once per
     * level. The result is identical to a fresh {@code mergedDocument()} call but costs a single
     * deep copy per mutation cycle rather than one per level (B-T1).
     */
    public static void spliceDerived(ObjectNode doc, String dotPath, JsonNode value) {
        setDerivedInDoc(doc, PathConverter.toSegments(dotPath), value);
    }

    /**
     * Splices a derived value into a merged document <b>without growing arrays</b>. Intermediate
     * objects are created as needed (so genuinely new derived nested fields appear), but an array
     * index at or beyond the current array length causes the whole entry to be skipped. This is
     * how {@link #mergedDocument()} and {@link Snapshot#mergedDocument()} avoid splicing phantom
     * array elements from stale derived-cache entries (e.g. after a wildcard array shrinks).
     */
    static void setDerivedInDoc(ObjectNode root, List<String> segments, JsonNode value) {
        if (segments.isEmpty()) return;

        JsonNode current = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            String seg     = segments.get(i);
            String nextSeg = segments.get(i + 1);

            if (current.isObject()) {
                ObjectNode obj   = (ObjectNode) current;
                JsonNode   child = obj.get(seg);
                if (child == null || child.isMissingNode() || child.isNull()) {
                    child = isNumeric(nextSeg)
                            ? JsonNodeFactory.instance.arrayNode()
                            : JsonNodeFactory.instance.objectNode();
                    obj.set(seg, child);
                }
                current = child;
            } else if (current.isArray()) {
                if (!isNumeric(seg)) return;
                ArrayNode arr = (ArrayNode) current;
                long      idx = readIndex(seg);
                if (idx < 0 || idx >= arr.size()) return;   // out of bounds → skip (no phantom element)
                JsonNode child = arr.get((int) idx);
                if (child == null || child.isNull() || child.isMissingNode()) {
                    child = isNumeric(nextSeg)
                            ? JsonNodeFactory.instance.arrayNode()
                            : JsonNodeFactory.instance.objectNode();
                    arr.set((int) idx, child);
                }
                current = child;
            } else {
                return;
            }
        }

        String leaf = segments.getLast();
        if (current.isObject()) {
            ((ObjectNode) current).set(leaf, value);
        } else if (current.isArray()) {
            if (!isNumeric(leaf)) return;
            ArrayNode arr = (ArrayNode) current;
            long      idx = readIndex(leaf);
            if (idx < 0 || idx >= arr.size()) return;       // out of bounds → skip
            arr.set((int) idx, value);
        }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (char c : s.toCharArray()) if (c < '0' || c > '9') return false;
        return true;
    }

    /**
     * Parses a digit-only segment as a long for the non-allocating read/splice paths
     * ({@link #removeInDoc}, {@link #setDerivedInDoc}). Returns {@code -1} for a value too large to be
     * a real array index — those paths only ever act when {@code idx < arr.size()}, so an out-of-range
     * (or overflowing) index is a safe no-op. The allocating write path uses {@link #arrayIndex}
     * instead, which enforces {@link #MAX_ARRAY_INDEX} as a typed {@link StateLimitExceededException}.
     */
    private static long readIndex(String seg) {
        try {
            return Long.parseLong(seg);
        } catch (NumberFormatException e) {
            return -1; // absurdly long digit string — cannot address any existing element
        }
    }

    // ── Structural write limits (audit SEC-1 / MEM-3) ───────────────────────────

    /**
     * Hard ceiling on the array index a single write may target, capping the null-padding a write
     * performs to reach that index. Overridable via the {@code valem.limits.max-array-index}
     * system property (default one million). A write beyond this throws
     * {@link StateLimitExceededException} <b>before</b> any allocation, so one small mutation like
     * {@code $.items[900000000]} can no longer exhaust the heap.
     */
    private static final int MAX_ARRAY_INDEX =
            Integer.getInteger("valem.limits.max-array-index", 1_000_000);

    /** The maximum array index a write may target before {@link StateLimitExceededException}. */
    public static int maxArrayIndex() { return MAX_ARRAY_INDEX; }

    /**
     * Parses an array-index segment, rejecting a negative, non-numeric, or out-of-bound index
     * before any array padding is attempted. Long-parsed first so an index above
     * {@link Integer#MAX_VALUE} is reported as a limit violation rather than a raw
     * {@code NumberFormatException}.
     */
    private static int arrayIndex(String seg) {
        long idx;
        try {
            idx = Long.parseLong(seg);
        } catch (NumberFormatException e) {
            throw new StateLimitExceededException("invalid array index: " + seg);
        }
        if (idx < 0 || idx > MAX_ARRAY_INDEX) {
            throw new StateLimitExceededException(
                    "array index " + idx + " exceeds maximum allowed (" + MAX_ARRAY_INDEX + ")");
        }
        return (int) idx;
    }

    private void requireTransaction() {
        if (transactionSnapshot == null) throw new IllegalStateException("No transaction is open.");
    }
}
