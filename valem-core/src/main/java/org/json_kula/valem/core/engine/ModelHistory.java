package org.json_kula.valem.core.engine;

import org.json_kula.valem.core.state.Snapshot;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Bounded in-memory history of model snapshots, ordered chronologically.
 *
 * <p>After each successful mutation cycle the runtime records a {@link Snapshot} with
 * the wall-clock time of the commit. When the buffer is full, the oldest entry is
 * evicted to make room.
 *
 * <p>Not thread-safe — access must be guarded by the same lock as {@link ModelRuntime}.
 */
public final class ModelHistory {

    /**
     * Default retained-entry cap. Each entry holds a full deep-copy snapshot, so this bounds
     * per-model steady heap (audit MEM-1). Overridable via {@code valem.history.max-entries};
     * set it to {@code 0} to disable temporal history entirely (point-in-time {@code ?at=} reads and
     * {@code GET /history} then return nothing) for deployments that do not use those endpoints.
     */
    static final int DEFAULT_MAX_SIZE =
            Math.max(0, Integer.getInteger("valem.history.max-entries", 50));

    /** A single history entry: the wall-clock time of the commit + the settled state. */
    public record Entry(Instant timestamp, Snapshot snapshot) {}

    private final int maxSize;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public ModelHistory() {
        this(DEFAULT_MAX_SIZE);
    }

    public ModelHistory(int maxSize) {
        if (maxSize < 0) throw new IllegalArgumentException("maxSize must be >= 0");
        this.maxSize = maxSize;
    }

    /**
     * Records a new snapshot at the given timestamp. Evicts the oldest entry when full;
     * a no-op when history is disabled ({@code maxSize == 0}).
     */
    public void record(Instant timestamp, Snapshot snapshot) {
        if (maxSize == 0) return;
        entries.addLast(new Entry(timestamp, snapshot));
        if (entries.size() > maxSize) entries.removeFirst();
    }

    /**
     * Returns the most recent snapshot whose timestamp is at or before {@code time},
     * or empty if no such entry exists.
     */
    public Optional<Snapshot> findAt(Instant time) {
        Entry result = null;
        for (Entry e : entries) {
            if (!e.timestamp().isAfter(time)) result = e;
            else break; // entries are in chronological order; no later entry can match
        }
        return Optional.ofNullable(result).map(Entry::snapshot);
    }

    /** Returns all recorded timestamps in chronological order. */
    public List<Instant> timestamps() {
        return entries.stream().map(Entry::timestamp).toList();
    }

    /** Returns all retained snapshots in chronological order (e.g. for the blob GC mark set). */
    public List<Snapshot> snapshots() {
        return entries.stream().map(Entry::snapshot).toList();
    }

    /** Number of entries currently stored. */
    public int size() { return entries.size(); }

    /** Clears all history entries (e.g. after an explicit restore disrupts the timeline). */
    public void clear() { entries.clear(); }
}
