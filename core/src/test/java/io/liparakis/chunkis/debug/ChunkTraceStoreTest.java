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
}
