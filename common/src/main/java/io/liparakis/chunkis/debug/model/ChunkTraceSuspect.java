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
 * @param suspectId Unique monotonic ID assigned when this suspect was first created.
 * @param originalFailureEvent The first event that created this suspect.
 * @param latestEvent The most recent event that updated this suspect.
 * @param chunkKey Chunk coordinates where the anomaly was detected.
 * @param regionKey Region coordinates, if known; may be refined on subsequent updates.
 * @param operationId Operation ID of the most recent associated save operation, or {@code null}.
 * @param reason Machine-readable cause category.
 * @param severity Highest severity seen across all occurrences.
 * @param firstSeenTimestampMillis Wall-clock time of the first occurrence.
 * @param lastSeenTimestampMillis Wall-clock time of the most recent occurrence.
 * @param occurrenceCount Number of times this suspect has been triggered.
 * @param copiedTimeline Trimmed, deduplicated timeline of events related to this suspect. Capped at {@code ChunkTraceStore.DEFAULT_SUSPECT_TIMELINE_CAPACITY}.
 * @param latestMessage Human-readable description from the most recent occurrence.
 */
public record ChunkTraceSuspect(
        long suspectId,
        ChunkTraceEvent originalFailureEvent,
        ChunkTraceEvent latestEvent,
        DebugChunkKey chunkKey,
        DebugRegionKey regionKey,
        String operationId,
        ChunkTraceReason reason,
        ChunkTraceSeverity severity,
        long firstSeenTimestampMillis,
        long lastSeenTimestampMillis,
        int occurrenceCount,
        List<ChunkTraceEvent> copiedTimeline,
        String latestMessage
) {

    /**
     * Performs copy of.
     */
    public ChunkTraceSuspect {
        // Defensive copy so callers cannot mutate the stored timeline.
        copiedTimeline = List.copyOf(copiedTimeline);
    }
}
