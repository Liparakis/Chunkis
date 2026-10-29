package io.liparakis.chunkis.integration.migration.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the {@link OfflineMcaCisTranslator} class, validating shape matching,
 * empty chunk check, region retirement, and NBT payload extraction.
 */
class OfflineMcaCisTranslatorTest {

    /**
     * A temporary directory used for testing file operations.
     */
    @TempDir
    Path tempDir;

    /**
     * Helper method to construct a mock {@link ChunkDelta} representing a migrated chunk.
     *
     * @param entityId the entity ID to add to the delta
     * @return a new ChunkDelta populated with mock data
     */
    private static ChunkDelta<BlockState, NbtCompound> migratedDelta(final String entityId) {
        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>(BlockState::isAir);

        final NbtCompound blockEntity = new NbtCompound();
        blockEntity.putString("id", "minecraft:chest");
        delta.addBlockEntityData(1, 70, 2, blockEntity);

        final NbtCompound entity = new NbtCompound();
        entity.putString("id", entityId);
        delta.addPendingEntity(entity);

        final NbtCompound auxiliary = new NbtCompound();
        auxiliary.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        final NbtCompound metadata = ChunkSnapshotBuilder.createMigratedChunkMetadata(
                null,
                auxiliary,
                false
        );
        delta.setChunkMetadata(metadata, false);
        delta.setSuppressInitialRepopulation(true);
        return delta;
    }

    /**
     * Tests that a migrated chunk shape match requires a matching payload, not just markers.
     */
    @Test
    void migratedChunkShapeRequiresMatchingPayloadNotJustMarkers() {
        final ChunkDelta<BlockState, NbtCompound> expected = migratedDelta("minecraft:pig");
        final ChunkDelta<BlockState, NbtCompound> matching = migratedDelta("minecraft:pig");
        final ChunkDelta<BlockState, NbtCompound> mismatched = migratedDelta("minecraft:cow");

        assertTrue(MigrationChunkValidator.matchesMigratedChunkShape(expected, matching)
                .valid());
        assertFalse(MigrationChunkValidator.matchesMigratedChunkShape(expected, mismatched)
                .valid());
    }

    /**
     * Tests that an omittable empty chunk correctly identifies when it has no blocks, block entities, or entities.
     */
    @Test
    void omittableEmptyChunkRequiresNoBlocksBlockEntitiesOrEntities() {
        final ChunkDelta<BlockState, NbtCompound> empty = new ChunkDelta<>(BlockState::isAir);
        final ChunkDelta<BlockState, NbtCompound> entityOnly = new ChunkDelta<>(BlockState::isAir);
        entityOnly.addPendingEntity(new NbtCompound());

        assertTrue(ChunkSnapshotBuilder.isOmittableEmptyChunk(empty));
        assertFalse(ChunkSnapshotBuilder.isOmittableEmptyChunk(entityOnly));
    }

    /**
     * Tests that region file retirement renames the MCA file to a backup file.
     *
     * @throws IOException if a file IO operation fails
     */
    @Test
    void retireRegionFileRenamesMcaToBackup() throws IOException {
        final Path mcaPath = tempDir.resolve("r.0.0.mca");
        Files.writeString(mcaPath, "chunkis");

        assertTrue(RegionFileRetirement.retire(mcaPath));

        assertFalse(Files.exists(mcaPath));
        assertTrue(Files.exists(tempDir.resolve("r.0.0.mca.backup")));
    }

    /**
     * A crash between backup copy and source delete leaves an identical backup;
     * retiring again must complete instead of leaving the source live.
     *
     * @throws IOException if a file IO operation fails
     */
    @Test
    void retireRegionFileCompletesWhenIdenticalBackupExists() throws IOException {
        final Path mcaPath = tempDir.resolve("r.1.0.mca");
        Files.writeString(mcaPath, "chunkis");
        Files.writeString(tempDir.resolve("r.1.0.mca.backup"), "chunkis");

        assertTrue(RegionFileRetirement.retire(mcaPath));
        assertFalse(Files.exists(mcaPath));
    }

    /**
     * A different existing backup is never overwritten and the source stays live.
     *
     * @throws IOException if a file IO operation fails
     */
    @Test
    void retireRegionFileKeepsSourceWhenDifferentBackupExists() throws IOException {
        final Path mcaPath = tempDir.resolve("r.2.0.mca");
        Files.writeString(mcaPath, "newer");
        Files.writeString(tempDir.resolve("r.2.0.mca.backup"), "older");

        assertFalse(RegionFileRetirement.retire(mcaPath));
        assertTrue(Files.exists(mcaPath));
        assertEquals("older", Files.readString(tempDir.resolve("r.2.0.mca.backup")));
    }

    /**
     * Tests that entity payload extraction successfully reads from the modern external entity list layout.
     */
    @Test
    void extractEntityPayloadsReadsModernExternalEntityList() {
        final NbtCompound entity = new NbtCompound();
        entity.putString("id", "minecraft:villager");

        final NbtList entities = new NbtList();
        entities.add(entity);

        final NbtCompound root = new NbtCompound();
        root.put("Entities", entities);

        assertEquals(1,
                ChunkSnapshotBuilder.extractEntityPayloads(root)
                        .size());
        assertEquals("minecraft:villager",
                ChunkSnapshotBuilder.extractEntityPayloads(root)
                        .getFirst()
                        .getString("id")
                        .orElseThrow());
    }

    /**
     * Tests that chunk payload root extraction falls back to the legacy "Level" compound.
     */
    @Test
    void chunkPayloadRootFallsBackToLegacyLevelCompound() {
        final NbtCompound level = new NbtCompound();
        level.putString("marker", "level-root");
        final NbtCompound root = new NbtCompound();
        root.put("Level", level);

        assertEquals("level-root",
                ChunkSnapshotBuilder.chunkPayloadRoot(root)
                        .getString("marker")
                        .orElseThrow());
    }

    /**
     * Verifies CPU reservation, heap budgeting, and region-count bounds.
     */
    @Test
    void workerCountReservesTwoProcessorsAndHonorsHeapLimit() {
        assertEquals(10, OfflineMcaCisTranslator.workerCountFor(20, 12, 24L * 1024 * 1024 * 1024));
        assertEquals(1, OfflineMcaCisTranslator.workerCountFor(20, 12, 1024L * 1024 * 1024));
        assertEquals(2, OfflineMcaCisTranslator.workerCountFor(20, 12, 3L * 1024 * 1024 * 1024));
        assertEquals(2, OfflineMcaCisTranslator.workerCountFor(2, 12, 24L * 1024 * 1024 * 1024));
    }
}
