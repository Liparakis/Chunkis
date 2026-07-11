package io.liparakis.chunkis.debug.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkTraceSuspect;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChunkTraceStoreTest {

    /**
     * Performs tear down.
     */
    @AfterEach
    void tearDown() {
        ChunkTraceStore.clear();
        ChunkTraceStore.clearSuspects();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    /**
     * Performs debug defaults to off and store starts empty.
     */
    @Test
    void debugDefaultsToOffAndStoreStartsEmpty() {
        assertThat(ChunkisDebugConfig.level()).isEqualTo(ChunkisDebugLevel.OFF);
        assertThat(ChunkTraceStore.latest(10)).isEmpty();
    }

    /**
     * Performs keeps only most recent events within capacity.
     */
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

    /**
     * Performs creates monotonic operation ids.
     */
    @Test
    void createsMonotonicOperationIds() {
        assertThat(ChunkTraceStore.nextOperationId("save")).isEqualTo("save-1");
        assertThat(ChunkTraceStore.nextOperationId("save")).isEqualTo("save-2");
        assertThat(ChunkTraceStore.nextOperationId("load")).isEqualTo("load-3");
    }

    /**
     * Performs snapshot returns oldest first.
     */
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

    /**
     * Performs records assertion for malformed save rejected event.
     */
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
        assertThat(ChunkTraceStore.latest(1)
                .getFirst()
                .message())
                .contains("save rejected without a machine-readable reason");
    }

    /**
     * Performs promotes suspicious chunks from failure and history signals.
     */
    @Test
    void promotesSuspiciousChunksFromFailureAndHistorySignals() {
        final DebugChunkKey savedThenMissing = new DebugChunkKey(8, 9);
        final DebugChunkKey dirtyUnload = new DebugChunkKey(-3, 4);

        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 1L, "main",
                ChunkisDebugDomain.REGION_STORAGE,
                ChunkTraceEventType.SAVE_FLUSH_COMPLETED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_WRITE,
                "TestSource", "stored",
                "minecraft:overworld",
                savedThenMissing,
                new DebugRegionKey(0, 0),
                "save-1",
                false,
                64
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 2L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_SOURCE_RESOLVED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NEITHER,
                "TestSource", "missing",
                "minecraft:overworld",
                savedThenMissing,
                new DebugRegionKey(0, 0),
                "load-1",
                null,
                null
        ));
        final long savedThenMissingId = ChunkTraceStore.suspect(savedThenMissing)
                .suspectId();
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 3L, "main",
                ChunkisDebugDomain.DIRTY_TRACKING,
                ChunkTraceEventType.TRACKER_STATE_UPDATED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.TRACKER_DIRTY_MAP_PUT,
                "TestSource", "dirty",
                "minecraft:overworld",
                dirtyUnload,
                null,
                null,
                true,
                null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 4L, "main",
                ChunkisDebugDomain.DIRTY_TRACKING,
                ChunkTraceEventType.TRACKER_STATE_UPDATED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.TRACKER_CHUNK_UNLOADED,
                "TestSource", "unloaded",
                "minecraft:overworld",
                dirtyUnload,
                null,
                null,
                true,
                null
        ));
        final long dirtyUnloadId = ChunkTraceStore.suspect(dirtyUnload)
                .suspectId();

        assertThat(ChunkTraceStore.suspects())
                .extracting(ChunkTraceSuspect::chunkKey)
                .containsExactly(dirtyUnload, savedThenMissing);
        assertThat(ChunkTraceStore.suspect(savedThenMissingId)
                .latestMessage())
                .contains("prior stored payload");
        assertThat(ChunkTraceStore.suspect(dirtyUnloadId)
                .latestMessage())
                .contains("without queued or flushed save evidence");
    }

    /**
     * Performs retained suspect snapshot survives ring rotation.
     */
    @Test
    void retainedSuspectSnapshotSurvivesRingRotation() {
        ChunkTraceStore.setCapacityForTests(3);
        final DebugChunkKey chunkKey = new DebugChunkKey(23, -9);

        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 1L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "TestSource", "load started",
                "minecraft:overworld",
                chunkKey,
                new DebugRegionKey(0, -1),
                "load-23",
                null,
                null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 2L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.RESTORE_COMPLETED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.RESTORE_EMPTY_RESULT,
                "TestSource", "restore applied zero blocks",
                "minecraft:overworld",
                chunkKey,
                new DebugRegionKey(0, -1),
                "load-23",
                null,
                null
        ));

        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(chunkKey);
        assertThat(suspect).isNotNull();

        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 3L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "Noise", "n1",
                null, null, null, null, null, null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 4L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "Noise", "n2",
                null, null, null, null, null, null
        ));
        ChunkTraceStore.record(new ChunkTraceEvent(
                0L, 5L, "main",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "Noise", "n3",
                null, null, null, null, null, null
        ));

        assertThat(ChunkTraceStore.findEvent(suspect.originalFailureEvent()
                .eventId())).isNull();
        assertThat(ChunkTraceStore.suspect(suspect.suspectId())
                .originalFailureEvent()
                .message())
                .contains("restore applied zero blocks");
        assertThat(ChunkTraceStore.suspectTimeline(suspect.suspectId()))
                .extracting(ChunkTraceEvent::message)
                .contains("load started", "restore applied zero blocks");
    }
}
