package io.liparakis.chunkis.debug;

import java.util.List;

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
    public ChunkTraceSuspect {
        copiedTimeline = List.copyOf(copiedTimeline);
    }
}
