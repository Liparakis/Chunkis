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
        Integer byteSize,
        PayloadWatchTarget payloadWatchTarget,
        String payloadWatchStage,
        String payloadWatchSummary
) {
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
                eventId,
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
                byteSize,
                null,
                null,
                null
        );
    }

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
                byteSize,
                payloadWatchTarget,
                payloadWatchStage,
                payloadWatchSummary
        );
    }
}
