package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDeltaOwnershipTest {

    @Test
    void placeholderDeltaIsNotChunkisOwned() {
        assertFalse(ChunkDeltaOwnership.hasChunkisOwnedState(new ChunkDelta<>()));
    }

    @Test
    void replayPayloadMakesDeltaChunkisOwned() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.addBlockChange(1, 64, 1, "stone");

        assertTrue(ChunkDeltaOwnership.hasChunkisOwnedState(delta));
    }

    @Test
    void persistedBaseMakesDeltaChunkisOwned() {
        final NbtCompound baseChunk = new NbtCompound();
        baseChunk.putString("Status", "full");
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        null,
                        true,
                        false,
                        baseChunk
                ),
                false
        );

        assertTrue(ChunkDeltaOwnership.hasChunkisOwnedState(delta));
        assertTrue(ChunkDeltaOwnership.shouldMirrorVanillaDirtyState(delta));
    }

    @Test
    void passivePlaceholderDoesNotMirrorVanillaDirtyState() {
        assertFalse(ChunkDeltaOwnership.shouldMirrorVanillaDirtyState(new ChunkDelta<>()));
    }
}
