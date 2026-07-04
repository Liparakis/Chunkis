package io.liparakis.chunkis.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link ChunkDebugOutput} class.
 */
final class ChunkDebugOutputTest {

    /**
     * Tests that the formatted inspect snapshot output correctly includes restore flags
     * for live, tracked, and disk versions.
     */
    @Test
    void formatChunkInspectSnapshotIncludesRestoreFlags() {
        final String line = ChunkDebugOutput.formatChunkInspectSnapshot(
                new ChunkDebugCommand.ChunkInspectSnapshot(
                        new DebugChunkKey(12, -4),
                        true,
                        "restorable=true, owned=true",
                        "restorable=true, owned=true",
                        "restorable=true, owned=false",
                        true,
                        null
                )
        );

        assertTrue(line.contains("chunk=12,-4"));
        assertTrue(line.contains("loaded=true"));
        assertTrue(line.contains("diskPresent=true"));
        assertTrue(line.contains("live=restorable=true, owned=true"));
        assertTrue(line.contains("tracked=restorable=true, owned=true"));
        assertTrue(line.contains("disk=restorable=true, owned=false"));
    }
}
