package io.liparakis.chunkis.world.restoration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

/**
 * Test class for verifying chunk restorer and restore block operations logic in {@link ChunkRestorer}
 * and {@link ChunkRestoreBlockOperations}.
 */
class ChunkRestorerTest {

    /**
     * Verifies that seeding block entities from a migrated authoritative chunk delta carries
     * the persisted payloads into the runtime delta.
     */
    @Test
    void seedMigratedAuthoritativeBlockEntitiesCarriesPersistedPayloadsIntoRuntimeDelta() {
        final ChunkDelta<BlockState, NbtCompound> protoDelta = new ChunkDelta<>();
        final ChunkDelta<BlockState, NbtCompound> runtimeDelta = new ChunkDelta<>();
        final NbtCompound metadata = new NbtCompound();
        final NbtCompound chest = new NbtCompound();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 103);
        chest.putInt("y", 93);
        chest.putInt("z", 213);
        CisNbtUtil.markMigratedAuthoritativeChunk(metadata);
        protoDelta.setChunkMetadata(metadata, false);
        protoDelta.addBlockEntityData(7, 93, 5, chest, false);

        ChunkRestorer.seedMigratedAuthoritativeBlockEntities(protoDelta, runtimeDelta);

        assertEquals(1,
                runtimeDelta.getBlockEntities()
                        .size());
    }

    /**
     * Verifies that the string description of a replay payload lists section indices,
     * block changes, and block entities correctly.
     */
    @Test
    void describeReplayPayloadListsSectionsBlockChangesAndBlockEntities() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        final NbtCompound chest = new NbtCompound();
        chest.putString("id", "minecraft:chest");

        delta.addBlockChange(1, 5, 3, "stone");
        delta.addBlockChange(4, 32, 6, "dirt");
        delta.addBlockEntityData(1, 5, 3, chest);

        final String description = ChunkRestorer.describeReplayPayload(delta);

        assertEquals(
                "sections=[0,2], blockChanges=[(1,5,3)=stone, (4,32,6)=dirt], blockEntities=[(1,5,3)=minecraft:chest]",
                description
        );
    }

    /**
     * Verifies that the block apply failure counters correct record and describe each failure reason.
     */
    @Test
    void blockApplyFailureCountersDescribeEveryFailureReason() {
        final String description = getFailureCountersDescription();

        assertTrue(description.contains("visited=2"));
        assertTrue(description.contains("applied=1"));
        assertTrue(description.contains("nullState=1"));
        assertTrue(description.contains("outOfBoundsY=1"));
        assertTrue(description.contains("invalidSectionIndex=1"));
        assertTrue(description.contains("nullSection=1"));
        assertTrue(description.contains("exception=1"));
    }

    /**
     * Helper method to instantiate and populate a {@link ChunkRestorer.BlockApplyFailureCounters} instance.
     *
     * @return the formatted description string of the failure counters
     */
    private String getFailureCountersDescription() {
        final ChunkRestorer.BlockApplyFailureCounters counters =
                new ChunkRestorer.BlockApplyFailureCounters();

        counters.recordVisitedInstruction();
        counters.recordVisitedInstruction();
        counters.recordAppliedBlock();
        counters.recordNullState();
        counters.recordOutOfBoundsY();
        counters.recordInvalidSectionIndex();
        counters.recordNullSection();
        counters.recordException();

        return counters.describe();
    }

    /**
     * Verifies that the touched section Y coordinates are correctly collected based on the chunk bottom Y.
     */
    @Test
    void collectTouchedSectionYCoordinatesUseChunkBottomSectionY() {
        final boolean[] touchedSections = {true, false, true, false, false, true};

        final List<Integer> sectionYs = ChunkRestorer.collectTouchedSectionYCoordinates(
                -64,
                touchedSections
        );

        assertEquals(
                List.of(
                        -4,
                        -2,
                        1
                ),
                sectionYs
        );
    }

    /**
     * Verifies that collecting section Y coordinates needing refresh returns the entire chunk's sections
     * when a base snapshot was used.
     */
    @Test
    void collectSectionYCoordinatesNeedingRefreshReturnsWholeChunkWhenBaseSnapshotWasUsed() {
        final List<Integer> sectionYs = ChunkRestorer.collectSectionYCoordinatesNeedingRefresh(
                -64,
                new boolean[]{false, true, false},
                true
        );

        assertEquals(List.of(-4, -3, -2), sectionYs);
    }

    /**
     * Verifies the threshold logic for determining when to use bulk chunk refresh.
     */
    @Test
    void shouldUseBulkRefreshWhenExplicitDeltaIsDense() {
        assertTrue(ChunkRestorer.shouldUseBulkRefresh(1536, 2, 128, false));
        assertFalse(ChunkRestorer.shouldUseBulkRefresh(1535, 2, 128, false));
        assertFalse(ChunkRestorer.shouldUseBulkRefresh(1536, 2, 95, false));
    }

    /**
     * Verifies that section local index mapping matches the expected layout.
     */
    @Test
    void sectionLocalIndexMatchesRestoreIterationLayout() {
        assertEquals(0, ChunkRestoreBlockOperations.toSectionLocalIndex(0, 0, 0));
        assertEquals(1, ChunkRestoreBlockOperations.toSectionLocalIndex(1, 0, 0));
        assertEquals(16, ChunkRestoreBlockOperations.toSectionLocalIndex(0, 0, 1));
        assertEquals(256, ChunkRestoreBlockOperations.toSectionLocalIndex(0, 1, 0));
        assertEquals(4095, ChunkRestoreBlockOperations.toSectionLocalIndex(15, 15, 15));
    }

}
