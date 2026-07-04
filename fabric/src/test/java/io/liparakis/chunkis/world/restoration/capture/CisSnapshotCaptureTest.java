package io.liparakis.chunkis.world.restoration.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

/**
 * Test class for verifying chunk snapshot capture logic in {@link CisSnapshotCapture}.
 */
class CisSnapshotCaptureTest {

    /**
     * Verifies the translation from section index and local Y offset to absolute world Y coordinates.
     */
    @Test
    void convertsSectionIndexToAbsoluteBlockY() {
        assertEquals(-64, CisSnapshotCapture.toWorldY(-64, 0, 0));
        assertEquals(-49, CisSnapshotCapture.toWorldY(-64, 0, 15));
        assertEquals(-48, CisSnapshotCapture.toWorldY(-64, 1, 0));
        assertEquals(319, CisSnapshotCapture.toWorldY(-64, 23, 15));
    }

    /**
     * Verifies detection of suspicious baseline size shrinkage.
     */
    @Test
    void detectsSuspiciousBaselineShrink() {
        assertTrue(CisSnapshotCapture.isSuspiciousBaselineShrink(1000, 599));
        assertFalse(CisSnapshotCapture.isSuspiciousBaselineShrink(1000, 600));
        assertFalse(CisSnapshotCapture.isSuspiciousBaselineShrink(0, 0));
    }

    /**
     * Verifies that creating authoritative snapshot metadata correctly drops the persisted base chunk
     * and marks the chunk as having a full block baseline.
     */
    @Test
    void authoritativeSnapshotMetadataDropsPersistedBaseAndMarksFullBaseline() {
        final NbtCompound structures = new NbtCompound();
        structures.put(CisNbtUtil.STRUCTURE_REFERENCES_KEY, new NbtCompound());
        final NbtCompound existingMetadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                structures,
                true,
                false,
                new NbtCompound(),
                true
        );

        final NbtCompound metadata = CisSnapshotCapture.createAuthoritativeSnapshotMetadata(existingMetadata, false);

        assertTrue(CisNbtUtil.hasFullBlockBaseline(metadata));
        assertNull(CisNbtUtil.extractPersistedBaseChunkNbt(metadata));
        assertEquals(structures, CisNbtUtil.extractPersistedStructureMetadata(metadata));
        assertFalse(CisNbtUtil.hasPersistedPortalChunk(metadata));
    }

    /**
     * Verifies that migrated auxiliary metadata (such as chunk status and post processing flags)
     * is correctly preserved during authoritative snapshot metadata creation.
     */
    @Test
    void authoritativeSnapshotMetadataPreservesMigratedAuxiliaryMetadata() {
        final NbtCompound existingMetadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                null,
                true,
                false,
                null,
                true
        );
        final NbtCompound auxiliary = new NbtCompound();
        auxiliary.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        auxiliary.putString("PostProcessing", "kept");
        CisNbtUtil.putPreservedAuxiliaryChunkNbt(existingMetadata, auxiliary);
        CisNbtUtil.markMigratedAuthoritativeChunk(existingMetadata);

        final NbtCompound metadata = CisSnapshotCapture.createAuthoritativeSnapshotMetadata(existingMetadata, false);

        assertTrue(CisNbtUtil.isMigratedAuthoritativeChunk(metadata));
        final NbtCompound preservedAuxiliary = CisNbtUtil.extractPreservedAuxiliaryChunkNbt(metadata);
        assertNotNull(preservedAuxiliary);
        assertEquals("minecraft:full",
                preservedAuxiliary.getString(CisNbtUtil.STATUS_KEY)
                        .orElseThrow());
        assertEquals("kept",
                preservedAuxiliary.getString("PostProcessing")
                        .orElseThrow());
    }

    /**
     * Verifies that the decision to persist base chunk info is made correctly based on block entity count.
     */
    @Test
    void snapshotKeepsPersistedBaseForChunksWithBlockEntities() {
        assertTrue(CisSnapshotCapture.shouldPersistBaseChunkForSnapshot(1));
        assertFalse(CisSnapshotCapture.shouldPersistBaseChunkForSnapshot(0));
    }

    /**
     * Verifies that migrated block entities are preserved when the live chunk has not instantiated them yet.
     */
    @Test
    void preservesMigratedBlockEntitiesWhenLiveChunkHasNotInstantiatedAnyYet() {
        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                null,
                true,
                true,
                null,
                false
        );
        CisNbtUtil.markMigratedAuthoritativeChunk(metadata);

        final io.liparakis.chunkis.core.ChunkDelta<net.minecraft.block.BlockState, NbtCompound> delta =
                new io.liparakis.chunkis.core.ChunkDelta<>(net.minecraft.block.BlockState::isAir);
        delta.setChunkMetadata(metadata, false);
        final NbtCompound chest = new NbtCompound();
        chest.putString("id", "minecraft:chest");
        delta.addBlockEntityData(1, 70, 2, chest, false);

        assertTrue(CisSnapshotCapture.shouldPreserveMigratedBlockEntities(delta, true));
        assertFalse(CisSnapshotCapture.shouldPreserveMigratedBlockEntities(delta, false));
    }

    /**
     * Verifies that block entities are rehydrated and restored back into the chunk delta after a snapshot clear.
     */
    @Test
    void restoreBlockEntitiesRehydratesPayloadAfterSnapshotClear() {
        final io.liparakis.chunkis.core.ChunkDelta<net.minecraft.block.BlockState, NbtCompound> delta =
                new io.liparakis.chunkis.core.ChunkDelta<>(net.minecraft.block.BlockState::isAir);
        final NbtCompound chest = new NbtCompound();
        chest.putString("id", "minecraft:chest");
        delta.addBlockEntityData(1, 70, 2, chest, false);

        final Long2ObjectOpenHashMap<NbtCompound> preserved =
                new Long2ObjectOpenHashMap<>(delta.getBlockEntities());
        delta.clearBlockPayloads(false);
        CisSnapshotCapture.restoreBlockEntities(delta, preserved);

        assertEquals(1,
                delta.getBlockEntities()
                        .size());
        assertEquals("minecraft:chest", delta.getBlockEntities()
                .get(io.liparakis.chunkis.core.BlockInstruction.packPos(1, 70, 2))
                .getString("id")
                .orElseThrow());
    }
}
