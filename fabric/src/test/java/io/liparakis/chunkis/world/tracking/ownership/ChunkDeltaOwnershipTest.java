package io.liparakis.chunkis.world.tracking.ownership;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChunkDeltaOwnership}, validating the resolution of
 * Chunkis ownership status and vanilla dirty state mirroring for a chunk delta.
 */
class ChunkDeltaOwnershipTest {

    /**
     * Verifies that a placeholder delta without any edits or metadata
     * is not considered Chunkis-owned.
     */
    @Test
    void placeholderDeltaIsNotChunkisOwned() {
        assertFalse(ChunkDeltaOwnership.hasChunkisOwnedState(new ChunkDelta<>()));
    }

    /**
     * Verifies that adding block changes (a replay payload) to a delta
     * does not make it Chunkis-owned or prompt vanilla dirty state mirroring by default.
     */
    @Test
    void replayPayloadMakesDeltaChunkisOwned() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.addBlockChange(1, 64, 1, "stone");

        assertFalse(ChunkDeltaOwnership.hasChunkisOwnedState(delta));
        assertFalse(ChunkDeltaOwnership.shouldMirrorVanillaDirtyState(delta));
    }

    /**
     * Verifies that claiming ownership explicitly on a delta makes it
     * Chunkis-owned and configures it to mirror the vanilla dirty state.
     */
    @Test
    void explicitOwnershipClaimMakesDeltaChunkisOwned() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.addBlockChange(1, 64, 1, "stone");
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");

        assertTrue(ChunkDeltaOwnership.hasChunkisOwnedState(delta));
        assertTrue(ChunkDeltaOwnership.shouldMirrorVanillaDirtyState(delta));
    }

    /**
     * Verifies that a delta with persisted base metadata is not automatically
     * considered Chunkis-owned or mirroring vanilla dirty state.
     */
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

        assertFalse(ChunkDeltaOwnership.hasChunkisOwnedState(delta));
        assertFalse(ChunkDeltaOwnership.shouldMirrorVanillaDirtyState(delta));
    }

    /**
     * Verifies that a restorable Chunkis state can be detected from a delta's metadata
     * even if there is no active ownership claim.
     */
    @Test
    void restorableStateRemainsDetectableWithoutOwnershipClaim() {
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

        assertTrue(ChunkDeltaOwnership.hasRestorableChunkisState(delta));
    }

    /**
     * Verifies that a passive placeholder delta does not mirror the vanilla dirty state.
     */
    @Test
    void passivePlaceholderDoesNotMirrorVanillaDirtyState() {
        assertFalse(ChunkDeltaOwnership.shouldMirrorVanillaDirtyState(new ChunkDelta<>()));
    }
}
