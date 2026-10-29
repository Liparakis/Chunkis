package io.liparakis.chunkis.world.entity.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

/**
 * Tests the no-change detection that keeps unchanged entity captures from dirtying a delta.
 */
class LiveEntitySnapshotCaptureTest {

    /**
     * Builds a minimal entity payload.
     *
     * @param id   entity type id
     * @param tick a field that changes for live mobs
     * @return payload compound
     */
    private static NbtCompound entity(final String id, final int tick) {
        final NbtCompound nbt = new NbtCompound();
        nbt.putString("id", id);
        nbt.putInt("Age", tick);
        return nbt;
    }

    /**
     * Identical payloads in a different runtime order count as unchanged.
     */
    @Test
    void sameEntitiesIgnoresRuntimeOrder() {
        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();
        delta.putEntity(1, entity("minecraft:item_frame", 0));
        delta.putEntity(2, entity("minecraft:armor_stand", 0));

        assertTrue(LiveEntitySnapshotCapture.sameEntities(delta, List.of(),
                List.of(entity("minecraft:armor_stand", 0), entity("minecraft:item_frame", 0)), List.of()));
    }

    /**
     * Any changed field, missing or extra entity, or pending change is a real change.
     */
    @Test
    void sameEntitiesDetectsChanges() {
        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();
        delta.putEntity(1, entity("minecraft:cow", 5));

        assertFalse(LiveEntitySnapshotCapture.sameEntities(delta, List.of(),
                List.of(entity("minecraft:cow", 6)), List.of()));
        assertFalse(LiveEntitySnapshotCapture.sameEntities(delta, List.of(), List.of(), List.of()));
        assertFalse(LiveEntitySnapshotCapture.sameEntities(delta, List.of(),
                List.of(entity("minecraft:cow", 5), entity("minecraft:pig", 1)), List.of()));

        final NbtCompound pending = entity("minecraft:pig", 0);
        delta.addPendingEntity(pending);
        assertFalse(LiveEntitySnapshotCapture.sameEntities(delta, List.of(pending),
                List.of(entity("minecraft:cow", 5)), List.of()));
        assertTrue(LiveEntitySnapshotCapture.sameEntities(delta, List.of(pending),
                List.of(entity("minecraft:cow", 5)), List.of(pending)));
    }
}
