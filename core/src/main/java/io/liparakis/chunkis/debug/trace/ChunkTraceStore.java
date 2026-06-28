package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.*;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Central store for Chunkis debug trace events and suspected anomalies.
 *
 * <h2>Event ring buffer</h2>
 * <p>Events are stored in a fixed-size circular array ({@link #ring}). When the
 * buffer is full the oldest event is silently overwritten. The default capacity
 * is {@value #DEFAULT_CAPACITY} events. Reading always happens under
 * {@link #MONITOR}; write uses a compare-and-assign inside the same lock.</p>
 *
 * <h2>Suspects</h2>
 * <p>Certain event types automatically trigger "suspect" records that aggregate
 * anomaly information for a {@code (chunkKey, reason)} pair. Suspects are capped
 * at {@link #DEFAULT_SUSPECT_CAPACITY}; the least-recently-updated suspect is
 * evicted when the cap is exceeded. Each suspect carries a trimmed event
 * timeline capped at {@link #DEFAULT_SUSPECT_TIMELINE_CAPACITY} entries.</p>
 *
 * <h2>Threading</h2>
 * <p>All mutable state is guarded by {@link #MONITOR}. The three
 * {@link AtomicLong} counters ({@link #EVENT_IDS}, {@link #OPERATION_IDS},
 * {@link #SUSPECT_IDS}) are incremented without the lock; their values are used
 * only as unique IDs, so minor reordering under concurrent access is
 * inconsequential.</p>
 */
public final class ChunkTraceStore {

    /**
     * Default number of events retained in the ring buffer.
     */
    static final int DEFAULT_CAPACITY = 50_000;
    /**
     * Default maximum number of active suspects.
     */
    static final int DEFAULT_SUSPECT_CAPACITY = 256;
    /**
     * Maximum number of events retained per suspect timeline. Older events are
     * trimmed when the timeline exceeds this limit during a merge.
     */
    static final int DEFAULT_SUSPECT_TIMELINE_CAPACITY = 40;

    /**
     * Monotonic counter for {@link ChunkTraceEvent#eventId()}.
     */
    private static final AtomicLong EVENT_IDS = new AtomicLong();
    /**
     * Monotonic counter for operation IDs returned by {@link #nextOperationId}.
     */
    private static final AtomicLong OPERATION_IDS = new AtomicLong();
    /**
     * Monotonic counter for {@link ChunkTraceSuspect#suspectId()}.
     */
    private static final AtomicLong SUSPECT_IDS = new AtomicLong();

    private static final Object MONITOR = new Object();

    /**
     * Current ring buffer capacity. Replaced (with a new array) on resize.
     */
    private static int capacity = DEFAULT_CAPACITY;
    /**
     * Circular event buffer. Indices are accessed as {@code (writeIndex - 1 - i + capacity) % capacity}.
     */
    private static ChunkTraceEvent[] ring = new ChunkTraceEvent[DEFAULT_CAPACITY];
    /**
     * Number of valid events in {@link #ring}; capped at {@link #capacity}.
     */
    private static int size = 0;
    /**
     * Next write position in {@link #ring}. Wraps modulo {@link #capacity}.
     */
    private static int writeIndex = 0;

    /**
     * Current maximum number of tracked suspects.
     */
    private static int suspectCapacity = DEFAULT_SUSPECT_CAPACITY;
    /**
     * Suspects indexed by their monotonic ID. Bounded by {@link #suspectCapacity};
     * eviction is LRU by {@link ChunkTraceSuspect#lastSeenTimestampMillis()}.
     * Eviction is O(n) — acceptable given the small cap.
     */
    private static final Map<Long, ChunkTraceSuspect> SUSPECTS_BY_ID = new HashMap<>();
    /**
     * Maps {@code (chunkKey, reason)} to the suspect ID, allowing upsert
     * without a full scan of {@link #SUSPECTS_BY_ID}.
     */
    private static final Map<SuspectKey, Long> SUSPECT_IDS_BY_KEY = new HashMap<>();

    private ChunkTraceStore() {
        throw new AssertionError("Utility class");
    }

    /**
     * Records a pre-built {@link ChunkTraceEvent}, assigning it a monotonic
     * event ID if it does not already have one.
     *
     * <p>After storage, the event is checked against the Chunkis invariants.
     * A structural violation produces a synthetic
     * {@link ChunkTraceEventType#ASSERTION_FAILED} event that is also recorded
     * and inspected for suspect capture.</p>
     *
     * @param event the event to record; must not be {@code null}
     */
    public static void record(final ChunkTraceEvent event) {
        Objects.requireNonNull(event, "event");

        final ChunkTraceEvent stored = append(event);
        final ChunkTraceEvent assertion = maybeRecordInvariantFailure(stored);

        maybeCaptureSuspect(stored);
        if (assertion != null) {
            maybeCaptureSuspect(assertion);
        }
    }

    /**
     * Generates a unique operation ID with the given prefix.
     *
     * <p>The ID has the form {@code "<prefix>-<n>"} where {@code n} is a
     * monotonically increasing integer. Used to correlate save lifecycle events
     * (queue → flush-start → flush-end) across multiple trace records.</p>
     *
     * @param prefix label prefix; must not be {@code null}
     * @return a unique operation ID string
     */
    public static String nextOperationId(final String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return prefix + '-' + OPERATION_IDS.incrementAndGet();
    }

    /**
     * Constructs and records a non-payload-watch trace event.
     *
     * <p>The event is dropped without recording if
     * {@link ChunkisDebugConfig#allows} returns {@code false}.</p>
     *
     * @param domain      event domain
     * @param eventType   specific event type
     * @param severity    event severity
     * @param reason      machine-readable cause
     * @param source      short identifier for the emitting code location
     * @param message     human-readable description
     * @param worldId     world registry key string, or {@code null}
     * @param chunkKey    chunk coordinates, or {@code null}
     * @param regionKey   region coordinates, or {@code null}
     * @param operationId save-operation correlation ID, or {@code null}
     * @param dirtyState  dirty/clean flag, or {@code null}
     * @param byteSize    payload byte count, or {@code null}
     */
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
                domain, eventType, severity, reason,
                source, message, worldId, chunkKey, regionKey, operationId,
                dirtyState, byteSize, null, null, null
        );
    }

    /**
     * Constructs and records a trace event, optionally associated with a payload
     * watch target.
     *
     * <p>Payload-watch events bypass the {@link ChunkisDebugConfig#allows} gate
     * and are always recorded, so callers can observe watched positions even
     * when general tracing is disabled.</p>
     *
     * @param domain              event domain
     * @param eventType           specific event type
     * @param severity            event severity
     * @param reason              machine-readable cause
     * @param source              short identifier for the emitting code location
     * @param message             human-readable description
     * @param worldId             world registry key string, or {@code null}
     * @param chunkKey            chunk coordinates, or {@code null}
     * @param regionKey           region coordinates, or {@code null}
     * @param operationId         save-operation correlation ID, or {@code null}
     * @param dirtyState          dirty/clean flag, or {@code null}
     * @param byteSize            payload byte count, or {@code null}
     * @param payloadWatchTarget  watch target that triggered this event, or {@code null}
     * @param payloadWatchStage   human-readable stage label, or {@code null}
     * @param payloadWatchSummary human-readable payload summary, or {@code null}
     */
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
        // Payload-watch events bypass the level gate; all others are filtered.
        if (payloadWatchTarget == null && !ChunkisDebugConfig.allows(domain, severity)) {
            return;
        }

        record(new ChunkTraceEvent(
                0L,
                System.currentTimeMillis(),
                Thread.currentThread().getName(),
                domain, eventType, severity, reason,
                source, message,
                worldId, chunkKey, regionKey, operationId,
                dirtyState, byteSize,
                payloadWatchTarget, payloadWatchStage, payloadWatchSummary
        ));
    }

    /**
     * Returns up to {@code count} of the most recently recorded events,
     * newest first.
     *
     * @param count maximum number of events to return
     * @return list of events, newest first; never {@code null}
     */
    public static List<ChunkTraceEvent> latest(final int count) {
        return latestMatching(count, event -> true);
    }

    /**
     * Returns all events in chronological order (oldest first).
     *
     * @return full snapshot of the ring buffer; never {@code null}
     */
    public static List<ChunkTraceEvent> snapshot() {
        return snapshotMatching(event -> true);
    }

    /**
     * Returns up to {@code count} of the most recent failure events, newest
     * first.
     *
     * <p>Failure events are those matching {@link #isFailureEvent}.</p>
     *
     * @param count maximum number of events to return
     * @return list of failure events, newest first
     */
    public static List<ChunkTraceEvent> latestFailures(final int count) {
        return latestMatching(count, ChunkTraceStore::isFailureEvent);
    }

    /**
     * Returns up to {@code count} of the most recently recorded events that
     * satisfy {@code predicate}, newest first.
     *
     * @param count     maximum number of events to return; returns empty list if ≤ 0
     * @param predicate filter applied to each candidate event
     * @return filtered event list, newest first
     */
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
                final ChunkTraceEvent event = ringAt(i);
                if (event != null && predicate.test(event)) {
                    events.add(event);
                }
            }
            return events;
        }
    }

    /**
     * Returns all events that satisfy {@code predicate}, in chronological order
     * (oldest first).
     *
     * @param predicate filter applied to each candidate event
     * @return filtered event list, oldest first
     */
    public static List<ChunkTraceEvent> snapshotMatching(final Predicate<ChunkTraceEvent> predicate) {
        Objects.requireNonNull(predicate, "predicate");

        synchronized (MONITOR) {
            final List<ChunkTraceEvent> events = new ArrayList<>(size);
            // Iterate oldest-first: i = size-1 gives the oldest slot, i = 0 the newest.
            for (int i = size - 1; i >= 0; i--) {
                final ChunkTraceEvent event = ringAt(i);
                if (event != null && predicate.test(event)) {
                    events.add(event);
                }
            }
            return events;
        }
    }

    /**
     * Finds the event with the given {@code eventId} by scanning the ring buffer
     * from newest to oldest.
     *
     * <p>Returns {@code null} if the event has already been overwritten or
     * {@code eventId} is invalid (≤ 0).</p>
     *
     * @param eventId the event ID to look up
     * @return the matching event, or {@code null}
     */
    public static ChunkTraceEvent findEvent(final long eventId) {
        if (eventId <= 0L) {
            return null;
        }
        synchronized (MONITOR) {
            for (int i = 0; i < size; i++) {
                final ChunkTraceEvent event = ringAt(i);
                if (event != null && event.eventId() == eventId) {
                    return event;
                }
            }
        }
        return null;
    }

    /**
     * Clears all events from the ring buffer.
     *
     * <p>A new array is allocated to release references to old events.</p>
     */
    public static void clear() {
        synchronized (MONITOR) {
            ring = new ChunkTraceEvent[capacity];
            size = 0;
            writeIndex = 0;
        }
    }

    /**
     * Returns all active suspects sorted by {@link ChunkTraceSuspect#lastSeenTimestampMillis}
     * descending (then by {@code suspectId} descending as a tiebreaker), so the
     * most recently active anomaly is first.
     *
     * @return sorted snapshot of all suspects; never {@code null}
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
     * Returns the suspect with the given ID, or {@code null} if it has been
     * evicted or never existed.
     *
     * @param suspectId the ID to look up
     * @return the suspect, or {@code null}
     */
    public static ChunkTraceSuspect suspect(final long suspectId) {
        synchronized (MONITOR) {
            return SUSPECTS_BY_ID.get(suspectId);
        }
    }

    /**
     * Returns the most recently updated suspect for the given chunk, or
     * {@code null} if none exists.
     *
     * <p>A chunk may have multiple suspects with different reasons; this returns
     * the one with the highest {@code lastSeenTimestampMillis}.</p>
     *
     * @param chunkKey the chunk to query; must not be {@code null}
     * @return the most recent suspect for that chunk, or {@code null}
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
     * Returns the trimmed event timeline for the suspect with the given ID, or
     * an empty list if the suspect does not exist.
     *
     * @param suspectId the suspect ID
     * @return the suspect's timeline; never {@code null}
     */
    public static List<ChunkTraceEvent> suspectTimeline(final long suspectId) {
        final ChunkTraceSuspect s = suspect(suspectId);
        return s == null ? List.of() : s.copiedTimeline();
    }

    /**
     * Removes all tracked suspects.
     */
    public static void clearSuspects() {
        synchronized (MONITOR) {
            SUSPECTS_BY_ID.clear();
            SUSPECT_IDS_BY_KEY.clear();
        }
    }

    /**
     * Assigns an event ID (if not already set) and writes the event into the
     * ring buffer. Returns the stored instance.
     *
     * @param event the event to store
     * @return the stored event, with an assigned {@code eventId}
     */
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

    /**
     * If {@code event} violates a Chunkis structural invariant, records a
     * synthetic {@link ChunkTraceEventType#ASSERTION_FAILED} event and returns
     * it. Returns {@code null} if the event is structurally valid or is itself
     * an assertion event (preventing infinite recursion).
     *
     * <p>The assertion event inherits context fields (world, chunk, region,
     * operation, dirty, byte size, payload watch) from the triggering event so
     * it surfaces in the same filtered views.</p>
     *
     * @param event the event to validate
     * @return the synthetic assertion event, or {@code null}
     */
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
                event.worldId(), event.chunkKey(), event.regionKey(), event.operationId(),
                event.dirtyState(), event.byteSize(),
                event.payloadWatchTarget(), event.payloadWatchStage(), event.payloadWatchSummary()
        ));
    }

    /**
     * If {@code event} qualifies as a suspect trigger and has a chunk key,
     * creates or updates the matching {@link ChunkTraceSuspect}.
     *
     * @param event the event to evaluate
     */
    private static void maybeCaptureSuspect(final ChunkTraceEvent event) {
        if (event.chunkKey() == null) {
            return;
        }
        final Suspicion suspicion = describeSuspicion(event);
        if (suspicion == null) {
            return;
        }
        final List<ChunkTraceEvent> timeline = copyRelevantTimeline(event);
        upsertSuspect(event, suspicion, timeline);
    }

    /**
     * Returns {@code true} for event types that represent failures or anomalies,
     * used by {@link #latestFailures}.
     *
     * @param event the event to classify
     * @return {@code true} if this is a failure event
     */
    private static boolean isFailureEvent(final ChunkTraceEvent event) {
        return switch (event.eventType()) {
            case ASSERTION_FAILED, SAVE_REJECTED, SAVE_FLUSH_FAILED,
                 RESTORE_FAILED, CLIENT_SYNC_FAILED -> true;
            case RESTORE_COMPLETED -> event.reason() == ChunkTraceReason.RESTORE_EMPTY_RESULT;
            default -> false;
        };
    }

    /**
     * Returns the {@link Suspicion} descriptor for {@code event} if it should
     * trigger suspect capture, or {@code null} otherwise.
     *
     * <p>Some event types are unconditional suspects (e.g. assertion failures).
     * Others require additional ring-buffer history checks (e.g. detecting that
     * a load resolved to "neither" despite prior stored payloads).</p>
     *
     * @param event the candidate event
     * @return a {@link Suspicion} if the event warrants suspect capture, else {@code null}
     */
    private static Suspicion describeSuspicion(final ChunkTraceEvent event) {
        return switch (event.eventType()) {
            case ASSERTION_FAILED, SAVE_REJECTED, SAVE_FLUSH_FAILED,
                 RESTORE_FAILED, CLIENT_SYNC_FAILED -> new Suspicion(event.reason(), event.severity(), event.message());

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

            case TRACKER_STATE_UPDATED -> event.reason() == ChunkTraceReason.TRACKER_CHUNK_UNLOADED
                    && Boolean.TRUE.equals(event.dirtyState())
                    && !hadQueuedOrFlushedSinceDirty(event.chunkKey(), event.eventId())
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
     * Returns the event at ring-buffer offset {@code i} from the most recent
     * write position.
     *
     * <p>{@code i = 0} → most recently written event;
     * {@code i = size-1} → oldest retained event.</p>
     *
     * <p>Must be called with {@link #MONITOR} held.</p>
     *
     * @param i offset from newest (0 = newest, size-1 = oldest)
     * @return the event at that offset, or {@code null} if the slot is empty
     */
    private static ChunkTraceEvent ringAt(final int i) {
        return ring[(writeIndex - 1 - i + capacity) % capacity];
    }

    /**
     * Returns {@code true} if a {@link ChunkTraceEventType#REGION_WRITE_TX_END}
     * or {@link ChunkTraceEventType#SAVE_FLUSH_COMPLETED} event for {@code chunkKey}
     * exists in the ring buffer before {@code beforeEventId}.
     *
     * <p>Used to determine whether the "neither" load-source result is suspicious.</p>
     *
     * @param chunkKey      chunk key to match
     * @param beforeEventId only consider events with IDs strictly less than this
     * @return {@code true} if a prior stored-payload event exists
     */
    private static boolean hadPriorStoredPayload(final DebugChunkKey chunkKey, final long beforeEventId) {
        synchronized (MONITOR) {
            for (int i = size - 1; i >= 0; i--) {
                final ChunkTraceEvent event = ringAt(i);
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

    /**
     * Returns {@code true} if a save-queued or flush event for {@code chunkKey}
     * occurred after the most recent dirty event before {@code beforeEventId}.
     *
     * <p>The scan finds the most recent dirty window (a
     * {@link ChunkTraceEventType#DELTA_MARKED_DIRTY} or
     * {@link ChunkTraceEventType#TRACKER_STATE_UPDATED} +
     * {@link ChunkTraceReason#TRACKER_DIRTY_MAP_PUT} event) and checks whether a
     * save-queued or flush event followed it. Used to detect dirty chunks that
     * unloaded without any save evidence.</p>
     *
     * @param chunkKey      chunk key to match
     * @param beforeEventId only consider events with IDs strictly less than this
     * @return {@code true} if a save was queued or flushed after the dirty event
     */
    private static boolean hadQueuedOrFlushedSinceDirty(final DebugChunkKey chunkKey, final long beforeEventId) {
        boolean dirtyWindowOpen = false;
        boolean queuedOrFlushed = false;

        synchronized (MONITOR) {
            for (int i = size - 1; i >= 0; i--) {
                final ChunkTraceEvent event = ringAt(i);
                if (event == null || event.eventId() >= beforeEventId || !chunkKey.equals(event.chunkKey())) {
                    continue;
                }
                if (isDirtyMarkerEvent(event)) {
                    // Each dirty marker resets the window; saves must follow it.
                    dirtyWindowOpen = true;
                    queuedOrFlushed = false;
                    continue;
                }
                if (dirtyWindowOpen && isSaveProgressEvent(event)) {
                    queuedOrFlushed = true;
                }
            }
        }

        return queuedOrFlushed;
    }

    /**
     * Returns {@code true} if a confirmed-flush event for {@code chunkKey} and
     * {@code operationId} exists before {@code beforeEventId}.
     *
     * <p>Used to detect deltas marked clean before their save was confirmed.</p>
     *
     * @param chunkKey      chunk key to match
     * @param operationId   operation ID to match
     * @param beforeEventId only consider events with IDs strictly less than this
     * @return {@code true} if a flush-completion event for the operation exists
     */
    private static boolean hasConfirmedFlush(
            final DebugChunkKey chunkKey,
            final String operationId,
            final long beforeEventId
    ) {
        synchronized (MONITOR) {
            for (int i = size - 1; i >= 0; i--) {
                final ChunkTraceEvent event = ringAt(i);
                if (event == null || event.eventId() >= beforeEventId || !chunkKey.equals(event.chunkKey())) {
                    continue;
                }
                if (operationId.equals(event.operationId()) && isSaveFlushCompletedEvent(event)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code event} marks a chunk as dirty, opening a
     * new save-expectation window in {@link #hadQueuedOrFlushedSinceDirty}.
     *
     * @param event event to classify
     * @return {@code true} for dirty-marker events
     */
    private static boolean isDirtyMarkerEvent(final ChunkTraceEvent event) {
        return event.eventType() == ChunkTraceEventType.DELTA_MARKED_DIRTY
                || (event.eventType() == ChunkTraceEventType.TRACKER_STATE_UPDATED
                && event.reason() == ChunkTraceReason.TRACKER_DIRTY_MAP_PUT);
    }

    /**
     * Returns {@code true} if {@code event} constitutes evidence that a save
     * was queued or flushed, satisfying the dirty-window requirement in
     * {@link #hadQueuedOrFlushedSinceDirty}.
     *
     * @param event event to classify
     * @return {@code true} for save-progress events
     */
    private static boolean isSaveProgressEvent(final ChunkTraceEvent event) {
        return event.eventType() == ChunkTraceEventType.SAVE_QUEUED
                || event.eventType() == ChunkTraceEventType.REGION_WRITE_TX_END
                || event.eventType() == ChunkTraceEventType.SAVE_FLUSH_COMPLETED;
    }

    /**
     * Returns {@code true} if {@code event} represents a confirmed flush
     * completion, used by {@link #hasConfirmedFlush}.
     *
     * @param event event to classify
     * @return {@code true} for flush-completion events
     */
    private static boolean isSaveFlushCompletedEvent(final ChunkTraceEvent event) {
        return event.eventType() == ChunkTraceEventType.REGION_WRITE_TX_END
                || event.eventType() == ChunkTraceEventType.SAVE_FLUSH_COMPLETED;
    }

    /**
     * Builds a trimmed list of ring-buffer events relevant to {@code event} for
     * use as the initial or updated suspect timeline.
     *
     * <p>An event is included if it shares the same chunk key or operation ID as
     * {@code event}. The result is trimmed to the most recent
     * {@link #DEFAULT_SUSPECT_TIMELINE_CAPACITY} entries (chronological order is
     * preserved by the ring traversal, oldest to newest).</p>
     *
     * @param event the triggering event
     * @return trimmed list of related events, oldest first
     */
    private static List<ChunkTraceEvent> copyRelevantTimeline(final ChunkTraceEvent event) {
        final DebugChunkKey chunkKey = event.chunkKey();
        final String operationId = event.operationId();
        final List<ChunkTraceEvent> matches = new ArrayList<>(DEFAULT_SUSPECT_TIMELINE_CAPACITY);

        synchronized (MONITOR) {
            // oldest-first traversal (i = size-1 → 0)
            for (int i = size - 1; i >= 0; i--) {
                final ChunkTraceEvent candidate = ringAt(i);
                if (candidate == null) {
                    continue;
                }
                if (chunkKey.equals(candidate.chunkKey())
                        || (operationId != null && operationId.equals(candidate.operationId()))) {
                    matches.add(candidate);
                }
            }
        }

        final int fromIndex = Math.max(0, matches.size() - DEFAULT_SUSPECT_TIMELINE_CAPACITY);
        return new ArrayList<>(matches.subList(fromIndex, matches.size()));
    }

    /**
     * Creates or updates the suspect for the {@code (chunkKey, reason)} pair
     * derived from {@code event}.
     *
     * <p>If no suspect exists for the key, a new one is created and eviction is
     * triggered if the cap is exceeded. If one already exists, its occurrence
     * count, severity, timeline, and latest-event fields are updated.</p>
     *
     * @param event            the event that triggered suspect capture
     * @param suspicion        reason and severity descriptor
     * @param capturedTimeline relevant event history for this suspect
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
                // Should not happen: both maps are updated atomically under MONITOR.
                // Defensive: clean up and insert fresh.
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
     * Evicts the least-recently-updated suspect when the cap is exceeded.
     *
     * <p>Eviction is O(n) in the number of active suspects. With a cap of
     * {@value #DEFAULT_SUSPECT_CAPACITY} this is acceptable. Both maps are
     * updated atomically to keep them consistent.</p>
     *
     * <p>Must be called with {@link #MONITOR} held.</p>
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
     * Merges two event timelines by deduplicating on {@link ChunkTraceEvent#eventId}
     * and trimming to the most recent {@link #DEFAULT_SUSPECT_TIMELINE_CAPACITY} entries.
     *
     * <p>Entries from {@code existing} take precedence in insertion order;
     * {@code captured} entries are merged on top. The result is oldest-first.</p>
     *
     * @param existing the current suspect timeline
     * @param captured the newly captured timeline
     * @return merged, trimmed timeline oldest-first
     */
    private static List<ChunkTraceEvent> mergeTimeline(
            final List<ChunkTraceEvent> existing,
            final List<ChunkTraceEvent> captured
    ) {
        final Map<Long, ChunkTraceEvent> merged = new LinkedHashMap<>();
        for (final ChunkTraceEvent e : existing) merged.put(e.eventId(), e);
        for (final ChunkTraceEvent e : captured) merged.put(e.eventId(), e);

        final List<ChunkTraceEvent> timeline = new ArrayList<>(merged.values());
        final int fromIndex = Math.max(0, timeline.size() - DEFAULT_SUSPECT_TIMELINE_CAPACITY);
        return new ArrayList<>(timeline.subList(fromIndex, timeline.size()));
    }

    /**
     * Returns the more severe of two {@link ChunkTraceSeverity} values,
     * using enum ordinal ordering (higher ordinal = more severe).
     *
     * @param left  first severity
     * @param right second severity
     * @return the higher-ordinal value
     */
    private static ChunkTraceSeverity moreSevere(
            final ChunkTraceSeverity left,
            final ChunkTraceSeverity right
    ) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    /**
     * Describes why an event qualifies as a suspect trigger.
     *
     * @param reason   machine-readable cause
     * @param severity severity to assign or escalate to
     * @param message  human-readable description of the anomaly
     */
    private record Suspicion(
            ChunkTraceReason reason,
            ChunkTraceSeverity severity,
            String message
    ) {}

    /**
     * Composite key used to deduplicate suspects within the same chunk and cause.
     *
     * @param chunkKey chunk coordinates
     * @param reason   machine-readable cause category
     */
    private record SuspectKey(
            DebugChunkKey chunkKey,
            ChunkTraceReason reason
    ) {}

    /**
     * Resets the ring buffer to a given capacity and clears all state.
     *
     * <p>Package-private; intended for unit tests only.</p>
     *
     * @param newCapacity new buffer capacity; must be &gt; 0
     */
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

    /**
     * Resets all state to defaults.
     *
     * <p>Package-private; intended for unit tests only.</p>
     */
    public static void resetForTests() {
        synchronized (MONITOR) {
            suspectCapacity = DEFAULT_SUSPECT_CAPACITY;
        }
        setCapacityForTests(DEFAULT_CAPACITY);
    }
}
