package io.liparakis.chunkis.world;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
