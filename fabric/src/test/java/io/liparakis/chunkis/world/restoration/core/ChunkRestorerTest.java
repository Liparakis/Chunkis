package io.liparakis.chunkis.world.restoration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.List;

class ChunkRestorerTest {

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

    @Test
    void blockApplyFailureCountersDescribeEveryFailureReason() {
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

        final String description = counters.describe();

        assertTrue(description.contains("visited=2"));
        assertTrue(description.contains("applied=1"));
        assertTrue(description.contains("nullState=1"));
        assertTrue(description.contains("outOfBoundsY=1"));
        assertTrue(description.contains("invalidSectionIndex=1"));
        assertTrue(description.contains("nullSection=1"));
        assertTrue(description.contains("exception=1"));
    }

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

    @Test
    void collectSectionYCoordinatesNeedingRefreshReturnsWholeChunkWhenBaseSnapshotWasUsed() {
        final List<Integer> sectionYs = ChunkRestorer.collectSectionYCoordinatesNeedingRefresh(
                -64,
                new boolean[]{false, true, false},
                true
        );

        assertEquals(List.of(-4, -3, -2), sectionYs);
    }

    @Test
    void shouldUseBulkRefreshWhenExplicitDeltaIsDense() {
        assertTrue(ChunkRestorer.shouldUseBulkRefresh(1024, 2, false));
        assertFalse(ChunkRestorer.shouldUseBulkRefresh(1023, 2, false));
    }

    @Test
    void sectionLocalIndexMatchesRestoreIterationLayout() {
        assertEquals(0, ChunkRestoreBlockOperations.toSectionLocalIndex(0, 0, 0));
        assertEquals(1, ChunkRestoreBlockOperations.toSectionLocalIndex(1, 0, 0));
        assertEquals(16, ChunkRestoreBlockOperations.toSectionLocalIndex(0, 0, 1));
        assertEquals(256, ChunkRestoreBlockOperations.toSectionLocalIndex(0, 1, 0));
        assertEquals(4095, ChunkRestoreBlockOperations.toSectionLocalIndex(15, 15, 15));
    }

}
