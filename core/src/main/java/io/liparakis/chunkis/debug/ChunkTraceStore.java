package io.liparakis.chunkis.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class ChunkTraceStore {

    private static final int DEFAULT_CAPACITY = 50_000;
    private static final int DEFAULT_SUSPECT_CAPACITY = 256;
    private static final int DEFAULT_SUSPECT_TIMELINE_CAPACITY = 40;

    private static final AtomicLong EVENT_IDS = new AtomicLong();
    private static final AtomicLong OPERATION_IDS = new AtomicLong();
    private static final AtomicLong SUSPECT_IDS = new AtomicLong();

    private static final Object MONITOR = new Object();

    private static volatile int capacity = DEFAULT_CAPACITY;
    private static volatile ChunkTraceEvent[] ring = new ChunkTraceEvent[DEFAULT_CAPACITY];
    private static volatile int size = 0;
    private static volatile int writeIndex = 0;
    private static volatile int suspectCapacity = DEFAULT_SUSPECT_CAPACITY;

    private static final Map<Long, ChunkTraceSuspect> SUSPECTS_BY_ID = new HashMap<>();
    private static final Map<SuspectKey, Long> SUSPECT_IDS_BY_KEY = new HashMap<>();

    private ChunkTraceStore() {
        throw new AssertionError("Utility class");
    }

    public static ChunkTraceEvent record(final ChunkTraceEvent event) {
        Objects.requireNonNull(event, "event");

        final ChunkTraceEvent stored = append(event);
        final ChunkTraceEvent generatedAssertion = maybeRecordInvariantFailure(stored);

        maybeCaptureSuspect(stored);
        if (generatedAssertion != null) {
            maybeCaptureSuspect(generatedAssertion);
        }

        return stored;
    }

    public static String nextOperationId(final String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return prefix + '-' + OPERATION_IDS.incrementAndGet();
    }

    public static void trace(
            final ChunkisDebugDomain domain,
            final ChunkTraceEventType eventType,
            final ChunkTraceSeverity severity,
            final ChunkTraceReason reason,
            final String source,
            final String message,
            final String worldId,
            final DebugChunkKey chunkKey,
            final DebugRegionKey regionKey,
            final String operationId,
            final Boolean dirtyState,
            final Integer byteSize
    ) {
        trace(
                domain,
                eventType,
                severity,
                reason,
                source,
                message,
                worldId,
                chunkKey,
                regionKey,
                operationId,
                dirtyState,
                byteSize,
                null,
                null,
                null
        );
    }

    public static void trace(
            final ChunkisDebugDomain domain,
            final ChunkTraceEventType eventType,
            final ChunkTraceSeverity severity,
            final ChunkTraceReason reason,
            final String source,
            final String message,
            final String worldId,
            final DebugChunkKey chunkKey,
            final DebugRegionKey regionKey,
            final String operationId,
            final Boolean dirtyState,
            final Integer byteSize,
            final PayloadWatchTarget payloadWatchTarget,
            final String payloadWatchStage,
            final String payloadWatchSummary
    ) {
        if (!ChunkisDebugConfig.allows(domain, severity)) {
            return;
        }

        record(new ChunkTraceEvent(
                0L,
                System.currentTimeMillis(),
                Thread.currentThread().getName(),
                domain,
                eventType,
                severity,
                reason,
                source,
                message,
                worldId,
                chunkKey,
                regionKey,
                operationId,
                dirtyState,
                byteSize,
                payloadWatchTarget,
                payloadWatchStage,
                payloadWatchSummary
        ));
    }

    public static List<ChunkTraceEvent> latest(final int count) {
        return latestMatching(count, event -> true);
    }

    public static List<ChunkTraceEvent> snapshot() {
        return snapshotMatching(event -> true);
    }

    public static List<ChunkTraceSuspect> suspects() {
        synchronized (MONITOR) {
            final List<ChunkTraceSuspect> suspects = new ArrayList<>(SUSPECTS_BY_ID.values());
            suspects.sort(Comparator.comparingLong(ChunkTraceSuspect::lastSeenTimestampMillis)
                    .thenComparingLong(ChunkTraceSuspect::suspectId)
                    .reversed());
            return suspects;
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
            for (final ChunkTraceSuspect suspect : SUSPECTS_BY_ID.values()) {
                if (!suspect.chunkKey().equals(chunkKey)) {
                    continue;
                }
                if (newest == null || suspect.lastSeenTimestampMillis() > newest.lastSeenTimestampMillis()) {
                    newest = suspect;
                }
            }
        }
        return newest;
    }

    public static List<ChunkTraceEvent> suspectTimeline(final long suspectId) {
        final ChunkTraceSuspect suspect = suspect(suspectId);
        return suspect == null ? List.of() : suspect.copiedTimeline();
    }

    public static void clearSuspects() {
        synchronized (MONITOR) {
            SUSPECTS_BY_ID.clear();
            SUSPECT_IDS_BY_KEY.clear();
        }
    }

    public static List<ChunkTraceEvent> latestFailures(final int count) {
        return latestMatching(count, ChunkTraceStore::isFailureEvent);
    }

    public static ChunkTraceEvent findEvent(final long eventId) {
        if (eventId <= 0L) {
            return null;
        }

        synchronized (MONITOR) {
            for (int i = 0; i < size; i++) {
                final int index = (writeIndex - 1 - i + capacity) % capacity;
                final ChunkTraceEvent event = ring[index];
                if (event != null && event.eventId() == eventId) {
                    return event;
                }
            }
        }

        return null;
    }

    public static List<ChunkTraceEvent> latestMatching(
            final int count,
            final Predicate<ChunkTraceEvent> predicate
    ) {
        if (count <= 0) {
            return List.of();
        }
        Objects.requireNonNull(predicate, "predicate");

        synchronized (MONITOR) {
            final List<ChunkTraceEvent> events = new ArrayList<>(Math.min(count, size));
            for (int i = 0; i < size && events.size() < count; i++) {
                final int index = (writeIndex - 1 - i + capacity) % capacity;
                final ChunkTraceEvent event = ring[index];
                if (event != null && predicate.test(event)) {
                    events.add(event);
                }
            }
            return events;
        }
    }

    public static List<ChunkTraceEvent> snapshotMatching(final Predicate<ChunkTraceEvent> predicate) {
        Objects.requireNonNull(predicate, "predicate");

        synchronized (MONITOR) {
            final List<ChunkTraceEvent> events = new ArrayList<>(size);
            for (int i = size - 1; i >= 0; i--) {
                final int index = (writeIndex - 1 - i + capacity) % capacity;
                final ChunkTraceEvent event = ring[index];
                if (event != null && predicate.test(event)) {
                    events.add(event);
                }
            }
            return events;
        }
    }

    public static void clear() {
        synchronized (MONITOR) {
            ring = new ChunkTraceEvent[capacity];
            size = 0;
            writeIndex = 0;
        }
    }

    private static ChunkTraceEvent append(final ChunkTraceEvent event) {
        final ChunkTraceEvent stored = event.eventId() > 0
                ? event
                : event.withEventId(EVENT_IDS.incrementAndGet());

        synchronized (MONITOR) {
            ring[writeIndex] = stored;
            writeIndex = (writeIndex + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        }

        return stored;
    }

    private static ChunkTraceEvent maybeRecordInvariantFailure(final ChunkTraceEvent event) {
        if (event.eventType() == ChunkTraceEventType.ASSERTION_FAILED) {
            return null;
        }

        final String violation = ChunkTraceInvariants.describeEventViolation(event);
        if (violation == null) {
            return null;
        }

        return append(new ChunkTraceEvent(
                0L,
                System.currentTimeMillis(),
                Thread.currentThread().getName(),
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.INVALID_PAYLOAD,
                "ChunkTraceStore#record",
                violation,
                event.worldId(),
                event.chunkKey(),
                event.regionKey(),
                event.operationId(),
                event.dirtyState(),
                event.byteSize(),
                event.payloadWatchTarget(),
                event.payloadWatchStage(),
                event.payloadWatchSummary()
        ));
    }

    private static void maybeCaptureSuspect(final ChunkTraceEvent event) {
        if (event.chunkKey() == null) {
            return;
        }

        final Suspicion suspicion = describeSuspicion(event);
        if (suspicion == null) {
            return;
        }

        final List<ChunkTraceEvent> capturedTimeline = copyRelevantTimeline(event);
        upsertSuspect(event, suspicion, capturedTimeline);
    }

    private static boolean isFailureEvent(final ChunkTraceEvent event) {
        return switch (event.eventType()) {
            case ASSERTION_FAILED, SAVE_REJECTED, SAVE_FLUSH_FAILED, RESTORE_FAILED, CLIENT_SYNC_FAILED -> true;
            case RESTORE_COMPLETED -> event.reason() == ChunkTraceReason.RESTORE_EMPTY_RESULT;
            default -> false;
        };
    }

    private static Suspicion describeSuspicion(final ChunkTraceEvent event) {
        return switch (event.eventType()) {
            case ASSERTION_FAILED, SAVE_REJECTED, SAVE_FLUSH_FAILED, RESTORE_FAILED, CLIENT_SYNC_FAILED ->
                    new Suspicion(event.reason(), event.severity(), event.message());
            case RESTORE_COMPLETED -> event.reason() == ChunkTraceReason.RESTORE_EMPTY_RESULT
                    ? new Suspicion(event.reason(), ChunkTraceSeverity.ERROR, event.message())
                    : null;
            case LOAD_SOURCE_RESOLVED -> event.reason() == ChunkTraceReason.NEITHER
                    && hadPriorStoredPayload(event.chunkKey(), event.eventId())
                    ? new Suspicion(
                    ChunkTraceReason.NEITHER,
                    ChunkTraceSeverity.WARN,
                    "load resolved to neither after prior stored payload"
            )
                    : null;
            case DELTA_MARKED_CLEAN -> event.operationId() != null
                    && !hasConfirmedFlush(event.chunkKey(), event.operationId(), event.eventId())
                    ? new Suspicion(
                    ChunkTraceReason.DELTA_MARKED_SAVED,
                    ChunkTraceSeverity.WARN,
                    "delta marked clean before confirmed flush"
            )
                    : null;
            default -> null;
        };
    }

    private static boolean hadPriorStoredPayload(final DebugChunkKey chunkKey, final long beforeEventId) {
        synchronized (MONITOR) {
            for (int i = size - 1; i >= 0; i--) {
                final int index = (writeIndex - 1 - i + capacity) % capacity;
                final ChunkTraceEvent event = ring[index];
                if (event == null || event.eventId() >= beforeEventId || !chunkKey.equals(event.chunkKey())) {
                    continue;
                }
                if (event.eventType() == ChunkTraceEventType.REGION_WRITE_TX_END
                        || event.eventType() == ChunkTraceEventType.SAVE_FLUSH_COMPLETED) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasConfirmedFlush(
            final DebugChunkKey chunkKey,
            final String operationId,
            final long beforeEventId
    ) {
        synchronized (MONITOR) {
            for (int i = size - 1; i >= 0; i--) {
                final int index = (writeIndex - 1 - i + capacity) % capacity;
                final ChunkTraceEvent event = ring[index];
                if (event == null || event.eventId() >= beforeEventId || !chunkKey.equals(event.chunkKey())) {
                    continue;
                }
                if (!operationId.equals(event.operationId())) {
                    continue;
                }
                if (event.eventType() == ChunkTraceEventType.REGION_WRITE_TX_END
                        || event.eventType() == ChunkTraceEventType.SAVE_FLUSH_COMPLETED) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<ChunkTraceEvent> copyRelevantTimeline(final ChunkTraceEvent event) {
        final DebugChunkKey chunkKey = event.chunkKey();
        final String operationId = event.operationId();
        final List<ChunkTraceEvent> matches = new ArrayList<>(DEFAULT_SUSPECT_TIMELINE_CAPACITY);

        synchronized (MONITOR) {
            for (int i = size - 1; i >= 0; i--) {
                final int index = (writeIndex - 1 - i + capacity) % capacity;
                final ChunkTraceEvent candidate = ring[index];
                if (candidate == null) {
                    continue;
                }
                final boolean sameChunk = chunkKey.equals(candidate.chunkKey());
                final boolean sameOperation = operationId != null && operationId.equals(candidate.operationId());
                if (!sameChunk && !sameOperation) {
                    continue;
                }
                matches.add(candidate);
            }
        }

        final int fromIndex = Math.max(0, matches.size() - DEFAULT_SUSPECT_TIMELINE_CAPACITY);
        return new ArrayList<>(matches.subList(fromIndex, matches.size()));
    }

    private static void upsertSuspect(
            final ChunkTraceEvent event,
            final Suspicion suspicion,
            final List<ChunkTraceEvent> capturedTimeline
    ) {
        synchronized (MONITOR) {
            final SuspectKey key = new SuspectKey(event.chunkKey(), suspicion.reason());
            final Long suspectId = SUSPECT_IDS_BY_KEY.get(key);
            if (suspectId == null) {
                final long newSuspectId = SUSPECT_IDS.incrementAndGet();
                SUSPECT_IDS_BY_KEY.put(key, newSuspectId);
                SUSPECTS_BY_ID.put(
                        newSuspectId, new ChunkTraceSuspect(
                                newSuspectId,
                                event,
                                event,
                                event.chunkKey(),
                                event.regionKey(),
                                event.operationId(),
                                suspicion.reason(),
                                suspicion.severity(),
                                event.timestampMillis(),
                                event.timestampMillis(),
                                1,
                                capturedTimeline,
                                suspicion.message()
                        )
                );
                evictOldestSuspectIfNeeded();
                return;
            }

            final ChunkTraceSuspect existing = SUSPECTS_BY_ID.get(suspectId);
            if (existing == null) {
                SUSPECT_IDS_BY_KEY.remove(key);
                upsertSuspect(event, suspicion, capturedTimeline);
                return;
            }

            SUSPECTS_BY_ID.put(
                    suspectId, new ChunkTraceSuspect(
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
            for (final ChunkTraceSuspect suspect : SUSPECTS_BY_ID.values()) {
                if (oldest == null
                        || suspect.lastSeenTimestampMillis() < oldest.lastSeenTimestampMillis()
                        || (suspect.lastSeenTimestampMillis() == oldest.lastSeenTimestampMillis()
                        && suspect.suspectId() < oldest.suspectId())) {
                    oldest = suspect;
                    oldestId = suspect.suspectId();
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
        for (final ChunkTraceEvent event : existing) {
            merged.put(event.eventId(), event);
        }
        for (final ChunkTraceEvent event : captured) {
            merged.put(event.eventId(), event);
        }

        final List<ChunkTraceEvent> timeline = new ArrayList<>(merged.values());
        final int fromIndex = Math.max(0, timeline.size() - DEFAULT_SUSPECT_TIMELINE_CAPACITY);
        return new ArrayList<>(timeline.subList(fromIndex, timeline.size()));
    }

    private static ChunkTraceSeverity moreSevere(
            final ChunkTraceSeverity left,
            final ChunkTraceSeverity right
    ) {
        return left.ordinal() >= right.ordinal() ? left : right;
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

    static void setCapacityForTests(final int newCapacity) {
        if (newCapacity <= 0) {
            throw new IllegalArgumentException("newCapacity must be > 0");
        }

        synchronized (MONITOR) {
            capacity = newCapacity;
            ring = new ChunkTraceEvent[newCapacity];
            size = 0;
            writeIndex = 0;
            EVENT_IDS.set(0L);
            OPERATION_IDS.set(0L);
            SUSPECT_IDS.set(0L);
            SUSPECTS_BY_ID.clear();
            SUSPECT_IDS_BY_KEY.clear();
        }
    }

    public static void resetForTests() {
        synchronized (MONITOR) {
            suspectCapacity = DEFAULT_SUSPECT_CAPACITY;
        }
        setCapacityForTests(DEFAULT_CAPACITY);
    }
}
