package io.liparakis.chunkis.command;

import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.debug.DebugRegionKey;
import io.liparakis.chunkis.storage.AsyncCisSaveManager;
import io.liparakis.chunkis.storage.BaseChunkCaptureScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDebugCommandTest {

    @AfterEach
    void tearDown() {
        ChunkTraceWatchpoints.clear();
    }

    @Test
    void formatsStructuredEventTimelineLine() {
        final ChunkTraceEvent event = new ChunkTraceEvent(
                42L,
                1_717_171_717_000L,
                "Server thread",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_FLUSH_COMPLETED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_WRITE,
                "CisStorage#writePrepared",
                "flush completed",
                "minecraft:overworld",
                new DebugChunkKey(12, -8),
                new DebugRegionKey(0, -1),
                "save-12--8-7",
                Boolean.FALSE,
                512
        );

        final String formatted = ChunkDebugCommand.formatEvent(event);

        assertTrue(formatted.contains("#42"));
        assertTrue(formatted.contains("SAVE_FLUSH_COMPLETED"));
        assertTrue(formatted.contains("INFO/STORAGE_WRITE"));
        assertTrue(formatted.contains("world=minecraft:overworld"));
        assertTrue(formatted.contains("chunk=12,-8"));
        assertTrue(formatted.contains("region=0,-1"));
        assertTrue(formatted.contains("dirty=false"));
        assertTrue(formatted.contains("bytes=512"));
        assertTrue(formatted.contains("op=save-12--8-7"));
        assertTrue(formatted.contains("src=CisStorage#writePrepared"));
        assertTrue(formatted.contains("thread=Server thread"));
        assertTrue(formatted.contains("msg=flush completed"));
    }

    @Test
    void formatsWatchpointSummary() {
        ChunkTraceWatchpoints.watchChunk(new DebugChunkKey(7, -2));
        ChunkTraceWatchpoints.watchRegion(new DebugRegionKey(0, -1));

        final String formatted = ChunkDebugCommand.formatWatchpointSummary();

        assertTrue(formatted.contains("chunks=7,-2"));
        assertTrue(formatted.contains("regions=0,-1"));
    }

    @Test
    void formatsPendingSnapshotSummary() {
        final String formatted = ChunkDebugCommand.formatPendingSnapshot(
                new ChunkDebugCommand.PendingChunkSnapshot(
                        new DebugChunkKey(7, -2),
                        true,
                        new AsyncCisSaveManager.PendingSaveSnapshot(
                                new DebugChunkKey(7, -2),
                                "save-7--2-4",
                                4L,
                                true
                        ),
                        new BaseChunkCaptureScheduler.QueuedCaptureSnapshot(
                                new DebugChunkKey(7, -2),
                                true
                        )
                )
        );

        assertTrue(formatted.contains("chunk=7,-2"));
        assertTrue(formatted.contains("trackerDirty=true"));
        assertTrue(formatted.contains("asyncQueued=true"));
        assertTrue(formatted.contains("baseCaptureQueued=true"));
        assertTrue(formatted.contains("asyncOp=save-7--2-4"));
        assertTrue(formatted.contains("asyncGeneration=4"));
        assertTrue(formatted.contains("baseCaptureDirty=true"));
    }
}
