package io.liparakis.chunkis.command;

import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.debug.DebugRegionKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDebugCommandTest {

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
                null,
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
        assertTrue(formatted.contains("src=CisStorage#writePrepared"));
        assertTrue(formatted.contains("thread=Server thread"));
        assertTrue(formatted.contains("msg=flush completed"));
    }
}
