package io.liparakis.chunkis.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkTraceStoreTest {

    @AfterEach
    void tearDown() {
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    @Test
    void debugDefaultsToOffAndStoreStartsEmpty() {
        assertThat(ChunkisDebugConfig.level()).isEqualTo(ChunkisDebugLevel.OFF);
        assertThat(ChunkTraceStore.latest(10)).isEmpty();
    }

    @Test
    void keepsOnlyMostRecentEventsWithinCapacity() {
        ChunkTraceStore.setCapacityForTests(3);

        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 1L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "first",
                null, null, null, null, null, null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 2L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "second",
                null, null, null, null, null, null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 3L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "third",
                null, null, null, null, null, null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 4L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "fourth",
                null, null, null, null, null, null
        ));

        final List<ChunkTraceEvent> latest = ChunkTraceStore.latest(10);
        assertThat(latest).extracting(ChunkTraceEvent::message)
                .containsExactly("fourth", "third", "second");
        assertThat(latest).extracting(ChunkTraceEvent::eventId)
                .containsExactly(4L, 3L, 2L);
    }

    @Test
    void createsMonotonicOperationIds() {
        assertThat(ChunkTraceStore.nextOperationId("save")).isEqualTo("save-1");
        assertThat(ChunkTraceStore.nextOperationId("save")).isEqualTo("save-2");
        assertThat(ChunkTraceStore.nextOperationId("load")).isEqualTo("load-3");
    }

    @Test
    void snapshotReturnsOldestFirst() {
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 1L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "first",
                null, null, null, null, null, null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 2L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_FLUSH_COMPLETED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_WRITE,
                "TestSource", "second",
                "minecraft:overworld",
                new DebugChunkKey(1, 2),
                null,
                "save-2",
                null,
                32
        ));

        assertThat(ChunkTraceStore.snapshot()).extracting(ChunkTraceEvent::message)
                .containsExactly("first", "second");
    }

    @Test
    void recordsAssertionForMalformedSaveRejectedEvent() {
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 1L, "main",
                ChunkisDebugDomain.SAVE_GUARDS,
                ChunkTraceEventType.SAVE_REJECTED,
                ChunkTraceSeverity.WARN,
                ChunkTraceReason.NONE,
                "TestSource", "missing reason",
                "minecraft:overworld",
                new DebugChunkKey(4, -2),
                null,
                "save-1",
                true,
                null
        ));

        assertThat(ChunkTraceStore.latest(2))
                .extracting(ChunkTraceEvent::eventType)
                .containsExactly(
                        ChunkTraceEventType.ASSERTION_FAILED,
                        ChunkTraceEventType.SAVE_REJECTED
                );
        assertThat(ChunkTraceStore.latest(1).getFirst().message())
                .contains("save rejected without a machine-readable reason");
    }
}
