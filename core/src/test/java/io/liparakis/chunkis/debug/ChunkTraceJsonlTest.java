package io.liparakis.chunkis.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkTraceJsonlTest {

    @TempDir
    Path tempDir;

    @Test
    void serializesStableJsonFields() {
        final ChunkTraceEvent event = new ChunkTraceEvent(
                7L,
                1234L,
                "Server thread",
                ChunkisDebugDomain.REGION_STORAGE,
                ChunkTraceEventType.REGION_WRITE_TX_END,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_WRITE,
                "CisStorage#flush",
                "write ok",
                "minecraft:overworld",
                new DebugChunkKey(12, -8),
                new DebugRegionKey(0, -1),
                "save-7",
                Boolean.FALSE,
                512
        );

        final String json = ChunkTraceJsonl.toJsonLine(event);

        assertThat(json).contains("\"eventId\":7");
        assertThat(json).contains("\"threadName\":\"Server thread\"");
        assertThat(json).contains("\"domain\":\"REGION_STORAGE\"");
        assertThat(json).contains("\"eventType\":\"REGION_WRITE_TX_END\"");
        assertThat(json).contains("\"reason\":\"STORAGE_WRITE\"");
        assertThat(json).contains("\"worldId\":\"minecraft:overworld\"");
        assertThat(json).contains("\"chunkX\":12");
        assertThat(json).contains("\"chunkZ\":-8");
        assertThat(json).contains("\"regionX\":0");
        assertThat(json).contains("\"regionZ\":-1");
        assertThat(json).contains("\"operationId\":\"save-7\"");
        assertThat(json).contains("\"dirtyState\":false");
        assertThat(json).contains("\"byteSize\":512");
    }

    @Test
    void writesOneEventPerLine() throws IOException {
        final Path output = tempDir.resolve("chunkis/debug/trace.jsonl");
        final List<ChunkTraceEvent> events = List.of(
                new ChunkTraceEvent(
                        1L, 11L, "main",
                        ChunkisDebugDomain.CHUNK_LIFECYCLE,
                        ChunkTraceEventType.SAVE_TX_START,
                        ChunkTraceSeverity.INFO,
                        ChunkTraceReason.NONE,
                        "A", "first",
                        null, null, null, null, null, null
                ),
                new ChunkTraceEvent(
                        2L, 22L, "main",
                        ChunkisDebugDomain.REGION_STORAGE,
                        ChunkTraceEventType.SAVE_FLUSH_COMPLETED,
                        ChunkTraceSeverity.INFO,
                        ChunkTraceReason.STORAGE_WRITE,
                        "B", "second",
                        null, null, null, null, null, null
                )
        );

        ChunkTraceJsonl.write(output, events);

        assertThat(Files.readAllLines(output))
                .hasSize(2)
                .allMatch(line -> line.startsWith("{") && line.endsWith("}"));
    }
}
