package io.liparakis.chunkis.debug.watch;

import static org.assertj.core.api.Assertions.assertThat;

import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChunkTraceWatchpointsTest {

    @AfterEach
    void tearDown() {
        ChunkTraceWatchpoints.clear();
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
    }

    @Test
    void matchesWatchedChunkAndRegionEvents() {
        ChunkTraceWatchpoints.watchChunk(new DebugChunkKey(3, -2));
        ChunkTraceWatchpoints.watchRegion(new DebugRegionKey(0, -1));

        final ChunkTraceEvent chunkEvent = new ChunkTraceEvent(
                0L, 1L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "chunk",
                null, new DebugChunkKey(3, -2), null, null, null, null
        );
        final ChunkTraceEvent regionEvent = new ChunkTraceEvent(
                0L, 2L, "main",
                ChunkisDebugDomain.REGION_STORAGE,
                ChunkTraceEventType.REGION_WRITE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_WRITE,
                "TestSource", "region",
                null, null, new DebugRegionKey(0, -1), null, null, null
        );
        final ChunkTraceEvent missEvent = new ChunkTraceEvent(
                0L, 3L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "miss",
                null, new DebugChunkKey(9, 9), null, null, null, null
        );

        assertThat(ChunkTraceWatchpoints.matches(chunkEvent)).isTrue();
        assertThat(ChunkTraceWatchpoints.matches(regionEvent)).isTrue();
        assertThat(ChunkTraceWatchpoints.matches(missEvent)).isFalse();
    }

    @Test
    void latestMatchingReturnsOnlyWatchedEvents() {
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 1L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "miss",
                null, new DebugChunkKey(9, 9), null, null, null, null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 2L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "hit-1",
                null, new DebugChunkKey(4, 5), null, null, null, null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 3L, "main",
                ChunkisDebugDomain.REGION_STORAGE,
                ChunkTraceEventType.REGION_WRITE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_WRITE,
                "TestSource", "hit-2",
                null, null, new DebugRegionKey(1, 1), null, null, null
        ));

        ChunkTraceWatchpoints.watchChunk(new DebugChunkKey(4, 5));
        ChunkTraceWatchpoints.watchRegion(new DebugRegionKey(1, 1));

        assertThat(ChunkTraceStore.latestMatching(10, ChunkTraceWatchpoints::matches))
                .extracting(ChunkTraceEvent::message)
                .containsExactly("hit-2", "hit-1");
    }

    @Test
    void matchesWatchedPayloadEvents() {
        final PayloadWatchTarget blockTarget = PayloadWatchTarget.block("minecraft:overworld", 10, 64, -3);
        final PayloadWatchTarget entityTarget = PayloadWatchTarget.entity(
                "minecraft:overworld",
                "123e4567-e89b-12d3-a456-426614174000"
                                                                         );
        ChunkTraceWatchpoints.watchPayload(blockTarget);
        ChunkTraceWatchpoints.watchPayload(entityTarget);

        final ChunkTraceEvent payloadEvent = new ChunkTraceEvent(
                0L, 4L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.WATCH_CAPTURED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "payload",
                "minecraft:overworld",
                new DebugChunkKey(0, -1),
                null,
                "save-1",
                null,
                null,
                blockTarget,
                "capture",
                "summary"
        );

        assertThat(ChunkTraceWatchpoints.matches(payloadEvent)).isTrue();
        assertThat(ChunkTraceWatchpoints.watchedPayloadsForChunk(
                "minecraft:overworld",
                new DebugChunkKey(0, -1)
                                                                )).containsExactly(blockTarget);
        assertThat(ChunkTraceWatchpoints.watchedPayloads()).containsExactly(blockTarget, entityTarget);
    }
}
