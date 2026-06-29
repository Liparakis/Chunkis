package io.liparakis.chunkis.debug.model;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;

/**
 * An immutable trace event recorded by {@link ChunkTraceStore}.
 *
 * <p>Events are assigned a monotonic {@link #eventId} by {@code ChunkTraceStore}
 * at record time; events constructed outside the store use {@code eventId = 0}
 * as a sentinel, replaced via {@link #withEventId} before storage.</p>
 *
 * <p>Most fields are required. Optional fields ({@link #worldId},
 * {@link #chunkKey}, {@link #regionKey}, {@link #operationId},
 * {@link #dirtyState}, {@link #byteSize}, {@link #payloadWatchTarget},
 * {@link #payloadWatchStage}, {@link #payloadWatchSummary}) are {@code null}
 * when not applicable to a given event type.</p>
 *
 * <p>The 15-argument constructor omits payload watch fields for the common case
 * where no payload watch is active.</p>
 */
public record ChunkTraceEvent(
        /** Monotonic event ID assigned by {@link ChunkTraceStore}; 0 before storage. */
        long eventId,
        /** Wall-clock time when the event was recorded, in milliseconds since epoch. */
        long timestampMillis,
        /** Name of the thread that produced this event. */
        String threadName,
        /** Debug domain that categorises this event. */
        ChunkisDebugDomain domain,
        /** Specific event type within the domain. */
        ChunkTraceEventType eventType,
        /** Severity of this event. */
        ChunkTraceSeverity severity,
        /** Machine-readable cause or context for this event. */
        ChunkTraceReason reason,
        /** Short identifying string for the code location that emitted the event. */
        String source,
        /** Human-readable description of what occurred. */
        String message,
        /** Registry key string of the world, or {@code null} if not applicable. */
        String worldId,
        /** Chunk coordinates, or {@code null} if not applicable. */
        DebugChunkKey chunkKey,
        /** Region coordinates, or {@code null} if not applicable. */
        DebugRegionKey regionKey,
        /** Save-operation identifier, or {@code null} if not applicable. */
        String operationId,
        /**
         * Dirty/clean state associated with this event, or {@code null} if not
         * applicable. {@code Boolean.TRUE} = dirty, {@code Boolean.FALSE} = clean.
         */
        Boolean dirtyState,
        /** Payload byte size associated with this event, or {@code null} if not applicable. */
        Integer byteSize,
        /** Payload watch target that triggered this event, or {@code null}. */
        PayloadWatchTarget payloadWatchTarget,
        /** Human-readable stage label for payload watch events, or {@code null}. */
        String payloadWatchStage,
        /** Human-readable payload summary for watch events, or {@code null}. */
        String payloadWatchSummary
) {

    /**
     * Convenience constructor for non-payload-watch events. Equivalent to
     * the canonical constructor with {@link #payloadWatchTarget},
     * {@link #payloadWatchStage}, and {@link #payloadWatchSummary} set to
     * {@code null}.
     */
    public ChunkTraceEvent(
            final long eventId,
            final long timestampMillis,
            final String threadName,
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
        this(
                eventId, timestampMillis, threadName,
                domain, eventType, severity, reason,
                source, message,
                worldId, chunkKey, regionKey, operationId,
                dirtyState, byteSize,
                null, null, null
            );
    }

    /**
     * Returns a copy of this event with a different {@link #eventId}.
     *
     * <p>Used by {@link ChunkTraceStore} to assign a monotonic ID to events
     * that were constructed with the sentinel value {@code 0}.</p>
     *
     * @param newEventId the ID to assign
     * @return a new event identical to this one except for {@code eventId}
     */
    public ChunkTraceEvent withEventId(final long newEventId) {
        return new ChunkTraceEvent(
                newEventId, timestampMillis, threadName,
                domain, eventType, severity, reason,
                source, message,
                worldId, chunkKey, regionKey, operationId,
                dirtyState, byteSize,
                payloadWatchTarget, payloadWatchStage, payloadWatchSummary
        );
    }
}