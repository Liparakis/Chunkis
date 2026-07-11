package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkTraceSuspect;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages suspects, anomaly detection, and LRU suspect cache eviction.
 */
public final class ChunkTraceSuspectManager {

    /**
     * Stores default suspect capacity.
     */
    static final int DEFAULT_SUSPECT_CAPACITY = 256;

    /**
     * Stores suspect ids.
     */
    private static final AtomicLong SUSPECT_IDS = new AtomicLong();
    /**
     * Stores monitor.
     */
    private static final Object MONITOR = new Object();
    /**
     * Stores long.
     */
    private static final Map<Long, ChunkTraceSuspect> SUSPECTS_BY_ID = new HashMap<>();
    /**
     * Stores suspect key.
     */
    private static final Map<SuspectKey, Long> SUSPECT_IDS_BY_KEY = new HashMap<>();
    /**
     * Stores suspect capacity.
     */
    private static int suspectCapacity = DEFAULT_SUSPECT_CAPACITY;

    /**
     * Performs chunk trace suspect manager.
     */
    private ChunkTraceSuspectManager() {
        throw new AssertionError("Utility class");
    }

    /**
     * Performs suspects.
     */
    public static List<ChunkTraceSuspect> suspects() {
        synchronized (MONITOR) {
            final List<ChunkTraceSuspect> result = new ArrayList<>(SUSPECTS_BY_ID.values());
            result.sort(Comparator.comparingLong(ChunkTraceSuspect::lastSeenTimestampMillis)
                    .thenComparingLong(ChunkTraceSuspect::suspectId)
                    .reversed());
            return result;
        }
    }

    /**
     * Performs suspect.
     */
    public static ChunkTraceSuspect suspect(final long suspectId) {
        synchronized (MONITOR) {
            return SUSPECTS_BY_ID.get(suspectId);
        }
    }

    /**
     * Performs suspect.
     */
    public static ChunkTraceSuspect suspect(final DebugChunkKey chunkKey) {
        Objects.requireNonNull(chunkKey, "chunkKey");
        ChunkTraceSuspect newest = null;
        synchronized (MONITOR) {
            for (final ChunkTraceSuspect s : SUSPECTS_BY_ID.values()) {
                if (chunkKey.equals(s.chunkKey())
                        && (newest == null || s.lastSeenTimestampMillis() > newest.lastSeenTimestampMillis())) {
                    newest = s;
                }
            }
        }
        return newest;
    }

    /**
     * Performs suspect timeline.
     */
    public static List<ChunkTraceEvent> suspectTimeline(final long suspectId) {
        final ChunkTraceSuspect s = suspect(suspectId);
        return s == null ? List.of() : s.copiedTimeline();
    }

    /**
     * Performs clear suspects.
     */
    public static void clearSuspects() {
        synchronized (MONITOR) {
            SUSPECTS_BY_ID.clear();
            SUSPECT_IDS_BY_KEY.clear();
        }
    }

    /**
     * Performs maybe capture suspect.
     */
    public static void maybeCaptureSuspect(final ChunkTraceEvent event) {
        if (event.chunkKey() == null) {
            return;
        }
        final Suspicion suspicion = describeSuspicion(event);
        if (suspicion == null) {
            return;
        }
        final List<ChunkTraceEvent> timeline = ChunkTraceStore.copyRelevantTimeline(event);
        upsertSuspect(event, suspicion, timeline);
    }

    /**
     * Performs describe suspicion.
     */
    private static Suspicion describeSuspicion(final ChunkTraceEvent event) {
        return switch (event.eventType()) {
            case ASSERTION_FAILED, SAVE_REJECTED, SAVE_FLUSH_FAILED,
                 RESTORE_FAILED, CLIENT_SYNC_FAILED -> new Suspicion(event.reason(), event.severity(), event.message());

            case RESTORE_COMPLETED -> event.reason() == ChunkTraceReason.RESTORE_EMPTY_RESULT
                    ? new Suspicion(event.reason(), ChunkTraceSeverity.ERROR, event.message())
                    : null;

            case LOAD_SOURCE_RESOLVED -> event.reason() == ChunkTraceReason.NEITHER
                    && ChunkTraceStore.hadPriorStoredPayload(event.chunkKey(), event.eventId())
                    ? new Suspicion(
                    ChunkTraceReason.NEITHER,
                    ChunkTraceSeverity.WARN,
                    "load resolved to neither after prior stored payload"
            )
                    : null;

            case DELTA_MARKED_CLEAN -> event.operationId() != null
                    && !ChunkTraceStore.hasConfirmedFlush(event.chunkKey(), event.operationId(), event.eventId())
                    ? new Suspicion(
                    ChunkTraceReason.DELTA_MARKED_SAVED,
                    ChunkTraceSeverity.WARN,
                    "delta marked clean before confirmed flush"
            )
                    : null;

            case TRACKER_STATE_UPDATED -> event.reason() == ChunkTraceReason.TRACKER_CHUNK_UNLOADED
                    && Boolean.TRUE.equals(event.dirtyState())
                    && !ChunkTraceStore.hadQueuedOrFlushedSinceDirty(event.chunkKey(), event.eventId())
                    ? new Suspicion(
                    ChunkTraceReason.TRACKER_CHUNK_UNLOADED,
                    ChunkTraceSeverity.ERROR,
                    "dirty chunk unloaded without queued or flushed save evidence"
            )
                    : null;

            default -> null;
        };
    }

    /**
     * Performs upsert suspect.
     */
    private static void upsertSuspect(
            final ChunkTraceEvent event,
            final Suspicion suspicion,
            final List<ChunkTraceEvent> capturedTimeline
    ) {
        synchronized (MONITOR) {
            final SuspectKey key = new SuspectKey(event.chunkKey(), suspicion.reason());
            final Long existingId = SUSPECT_IDS_BY_KEY.get(key);

            if (existingId == null) {
                final long newId = SUSPECT_IDS.incrementAndGet();
                SUSPECT_IDS_BY_KEY.put(key, newId);
                SUSPECTS_BY_ID.put(
                        newId, new ChunkTraceSuspect(
                                newId, event, event,
                                event.chunkKey(), event.regionKey(), event.operationId(),
                                suspicion.reason(), suspicion.severity(),
                                event.timestampMillis(), event.timestampMillis(),
                                1, capturedTimeline, suspicion.message()
                        )
                );
                evictOldestSuspectIfNeeded();
                return;
            }

            final ChunkTraceSuspect existing = SUSPECTS_BY_ID.get(existingId);
            if (existing == null) {
                SUSPECT_IDS_BY_KEY.remove(key);
                upsertSuspect(event, suspicion, capturedTimeline);
                return;
            }

            SUSPECTS_BY_ID.put(
                    existingId, new ChunkTraceSuspect(
                            existing.suspectId(),
                            existing.originalFailureEvent(),
                            event,
                            existing.chunkKey(),
                            event.regionKey() != null ? event.regionKey() : existing.regionKey(),
                            event.operationId() != null ? event.operationId() : existing.operationId(),
                            existing.reason(),
                            moreSevere(existing.severity(), suspicion.severity()),
                            existing.firstSeenTimestampMillis(),
                            event.timestampMillis(),
                            existing.occurrenceCount() + 1,
                            mergeTimeline(existing.copiedTimeline(), capturedTimeline),
                            suspicion.message()
                    )
            );
        }
    }

    /**
     * Performs evict oldest suspect if needed.
     */
    private static void evictOldestSuspectIfNeeded() {
        while (SUSPECTS_BY_ID.size() > suspectCapacity) {
            long oldestId = -1L;
            ChunkTraceSuspect oldest = null;
            for (final ChunkTraceSuspect s : SUSPECTS_BY_ID.values()) {
                if (oldest == null
                        || s.lastSeenTimestampMillis() < oldest.lastSeenTimestampMillis()
                        || (s.lastSeenTimestampMillis() == oldest.lastSeenTimestampMillis()
                        && s.suspectId() < oldest.suspectId())) {
                    oldest = s;
                    oldestId = s.suspectId();
                }
            }
            if (oldest == null) {
                return;
            }
            SUSPECTS_BY_ID.remove(oldestId);
            SUSPECT_IDS_BY_KEY.remove(new SuspectKey(oldest.chunkKey(), oldest.reason()));
        }
    }

    /**
     * Performs merge timeline.
     */
    private static List<ChunkTraceEvent> mergeTimeline(
            final List<ChunkTraceEvent> existing,
            final List<ChunkTraceEvent> captured
    ) {
        final Map<Long, ChunkTraceEvent> merged = new LinkedHashMap<>();
        for (final ChunkTraceEvent e : existing) {
            merged.put(e.eventId(), e);
        }
        for (final ChunkTraceEvent e : captured) {
            merged.put(e.eventId(), e);
        }

        final List<ChunkTraceEvent> timeline = new ArrayList<>(merged.values());
        final int fromIndex = Math.max(0, timeline.size() - ChunkTraceStore.DEFAULT_SUSPECT_TIMELINE_CAPACITY);
        return new ArrayList<>(timeline.subList(fromIndex, timeline.size()));
    }

    /**
     * Performs more severe.
     */
    private static ChunkTraceSeverity moreSevere(
            final ChunkTraceSeverity left,
            final ChunkTraceSeverity right
    ) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    /**
     * Performs reset for tests.
     */
    public static void resetForTests() {
        synchronized (MONITOR) {
            suspectCapacity = DEFAULT_SUSPECT_CAPACITY;
            SUSPECT_IDS.set(0L);
            SUSPECTS_BY_ID.clear();
            SUSPECT_IDS_BY_KEY.clear();
        }
    }

    private record Suspicion(
            ChunkTraceReason reason,
            ChunkTraceSeverity severity,
            String message
    ) {

    }

    private record SuspectKey(
            DebugChunkKey chunkKey,
            ChunkTraceReason reason
    ) {

    }
}
