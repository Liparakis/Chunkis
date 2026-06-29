package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.debug.model.*;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;

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

    static final int DEFAULT_SUSPECT_CAPACITY = 256;

    private static final AtomicLong SUSPECT_IDS = new AtomicLong();
    private static final Object MONITOR = new Object();

    private static int suspectCapacity = DEFAULT_SUSPECT_CAPACITY;

    private static final Map<Long, ChunkTraceSuspect> SUSPECTS_BY_ID = new HashMap<>();
    private static final Map<SuspectKey, Long> SUSPECT_IDS_BY_KEY = new HashMap<>();

    private ChunkTraceSuspectManager() {
        throw new AssertionError("Utility class");
    }

    public static List<ChunkTraceSuspect> suspects() {
        synchronized (MONITOR) {
            final List<ChunkTraceSuspect> result = new ArrayList<>(SUSPECTS_BY_ID.values());
            result.sort(Comparator.comparingLong(ChunkTraceSuspect::lastSeenTimestampMillis)
                    .thenComparingLong(ChunkTraceSuspect::suspectId)
                    .reversed());
            return result;
        }
    }

    public static ChunkTraceSuspect suspect(final long suspectId) {
        synchronized (MONITOR) {
            return SUSPECTS_BY_ID.get(suspectId);
        }
    }

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

    public static List<ChunkTraceEvent> suspectTimeline(final long suspectId) {
        final ChunkTraceSuspect s = suspect(suspectId);
        return s == null ? List.of() : s.copiedTimeline();
    }

    public static void clearSuspects() {
        synchronized (MONITOR) {
            SUSPECTS_BY_ID.clear();
            SUSPECT_IDS_BY_KEY.clear();
        }
    }

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

    private static List<ChunkTraceEvent> mergeTimeline(
            final List<ChunkTraceEvent> existing,
            final List<ChunkTraceEvent> captured
    ) {
        final Map<Long, ChunkTraceEvent> merged = new LinkedHashMap<>();
        for (final ChunkTraceEvent e : existing) merged.put(e.eventId(), e);
        for (final ChunkTraceEvent e : captured) merged.put(e.eventId(), e);

        final List<ChunkTraceEvent> timeline = new ArrayList<>(merged.values());
        final int fromIndex = Math.max(0, timeline.size() - ChunkTraceStore.DEFAULT_SUSPECT_TIMELINE_CAPACITY);
        return new ArrayList<>(timeline.subList(fromIndex, timeline.size()));
    }

    private static ChunkTraceSeverity moreSevere(
            final ChunkTraceSeverity left,
            final ChunkTraceSeverity right
    ) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

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
    ) {}

    private record SuspectKey(
            DebugChunkKey chunkKey,
            ChunkTraceReason reason
    ) {}
}
