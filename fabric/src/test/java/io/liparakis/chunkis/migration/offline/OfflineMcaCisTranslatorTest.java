package io.liparakis.chunkis.migration.offline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.NbtList;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
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
     * Tests that a migrated chunk shape match requires a matching payload, not just markers.
     */
    @Test
    void migratedChunkShapeRequiresMatchingPayloadNotJustMarkers() {
        final ChunkDelta<BlockState, NbtCompound> expected = migratedDelta("minecraft:pig");
        final ChunkDelta<BlockState, NbtCompound> matching = migratedDelta("minecraft:pig");
        final ChunkDelta<BlockState, NbtCompound> mismatched = migratedDelta("minecraft:cow");

        assertTrue(OfflineMcaCisTranslator.matchesMigratedChunkShape(expected, matching)
                .valid());
        assertFalse(OfflineMcaCisTranslator.matchesMigratedChunkShape(expected, mismatched)
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

        assertTrue(OfflineMcaCisTranslator.isOmittableEmptyChunk(empty));
        assertFalse(OfflineMcaCisTranslator.isOmittableEmptyChunk(entityOnly));
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

        OfflineMcaCisTranslator.retireRegionFile(mcaPath);

        assertFalse(Files.exists(mcaPath));
        assertTrue(Files.exists(tempDir.resolve("r.0.0.mca.backup")));
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
                OfflineMcaCisTranslator.extractEntityPayloads(root)
                        .size());
        assertEquals("minecraft:villager",
                OfflineMcaCisTranslator.extractEntityPayloads(root)
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
                OfflineMcaCisTranslator.chunkPayloadRoot(root)
                        .getString("marker")
                        .orElseThrow());
    }

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
        final NbtCompound metadata = OfflineMcaCisTranslator.createMigratedChunkMetadata(
                null,
                auxiliary,
                false
        );
        delta.setChunkMetadata(metadata, false);
        delta.setSuppressInitialRepopulation(true);
        return delta;
    }
}
