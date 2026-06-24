package io.liparakis.chunkis.world;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisNbtUtil;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalChunkTrackerTest {

    @Test
    void keepsExistingAuthoritativeDeltaWhenIncomingReplacementIsWeaker() {
        final NbtCompound baseChunkNbt = new NbtCompound();
        baseChunkNbt.putString("status", "full");
        final ChunkDelta<String, NbtCompound> existing = new ChunkDelta<>();
        existing.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        null,
                        false,
                        false,
                        baseChunkNbt
                ),
                false
        );
        final ChunkDelta<String, NbtCompound> weakerIncoming = new ChunkDelta<>();

        assertTrue(GlobalChunkTracker.shouldKeepExistingAuthoritativeDelta(existing, weakerIncoming));
    }

    @Test
    void allowsReplacementWhenIncomingDeltaIsAlsoAuthoritative() {
        final ChunkDelta<String, NbtCompound> existing = new ChunkDelta<>();
        existing.markDirty();
        final ChunkDelta<String, NbtCompound> incoming = new ChunkDelta<>();
        incoming.markDirty();

        assertFalse(GlobalChunkTracker.shouldKeepExistingAuthoritativeDelta(existing, incoming));
    }
}
