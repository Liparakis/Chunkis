package io.liparakis.chunkis.debug.model;


import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;

import java.util.List;

/**
 * An immutable snapshot of a detected anomaly at a specific chunk location.
 *
 * <p>Suspects are created and updated by {@link ChunkTraceStore} when trace
 * events match known error patterns. Each suspect tracks its original failure
 * event, the most recent event that updated it, an occurrence count, and a
 * trimmed timeline of related events for diagnosis.</p>
 *
 * <p>Deduplication is keyed on {@code (chunkKey, reason)}: multiple events
 * with the same cause at the same chunk increment {@link #occurrenceCount}
 * rather than creating additional suspects.</p>
 */
public record ChunkTraceSuspect(
        /** Unique monotonic ID assigned when this suspect was first created. */
        long suspectId,
        /** The first event that created this suspect. */
        ChunkTraceEvent originalFailureEvent,
        /** The most recent event that updated this suspect. */
        ChunkTraceEvent latestEvent,
        /** Chunk coordinates where the anomaly was detected. */
        DebugChunkKey chunkKey,
        /** Region coordinates, if known; may be refined on subsequent updates. */
        DebugRegionKey regionKey,
        /** Operation ID of the most recent associated save operation, or {@code null}. */
        String operationId,
        /** Machine-readable cause category. */
        ChunkTraceReason reason,
        /** Highest severity seen across all occurrences. */
        ChunkTraceSeverity severity,
        /** Wall-clock time of the first occurrence. */
        long firstSeenTimestampMillis,
        /** Wall-clock time of the most recent occurrence. */
        long lastSeenTimestampMillis,
        /** Number of times this suspect has been triggered. */
        int occurrenceCount,
        /**
         * Trimmed, deduplicated timeline of events related to this suspect.
         * Capped at {@code ChunkTraceStore.DEFAULT_SUSPECT_TIMELINE_CAPACITY}.
         */
        List<ChunkTraceEvent> copiedTimeline,
        /** Human-readable description from the most recent occurrence. */
        String latestMessage
) {
    public ChunkTraceSuspect {
        // Defensive copy so callers cannot mutate the stored timeline.
        copiedTimeline = List.copyOf(copiedTimeline);
    }
}