package io.liparakis.chunkis.debug;

public record ChunkTraceEvent(
        long eventId,
        long timestampMillis,
        String threadName,
        ChunkisDebugDomain domain,
        ChunkTraceEventType eventType,
        ChunkTraceSeverity severity,
        ChunkTraceReason reason,
        String source,
        String message,
        String worldId,
        DebugChunkKey chunkKey,
        DebugRegionKey regionKey,
        String operationId,
        Boolean dirtyState,
        Integer byteSize
) {
    public ChunkTraceEvent withEventId(final long newEventId) {
        return new ChunkTraceEvent(
                newEventId,
                timestampMillis,
                threadName,
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
                byteSize
        );
    }
}
