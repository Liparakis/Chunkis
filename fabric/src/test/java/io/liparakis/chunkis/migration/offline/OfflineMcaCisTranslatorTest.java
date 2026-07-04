package io.liparakis.chunkis.migration.offline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineMcaCisTranslatorTest {

    @TempDir
    Path tempDir;

    @Test
    void migratedChunkShapeRequiresMatchingPayloadNotJustMarkers() {
        final ChunkDelta<BlockState, NbtCompound> expected = migratedDelta();
        final ChunkDelta<BlockState, NbtCompound> matching = migratedDelta();
        final ChunkDelta<BlockState, NbtCompound> withEntityPayload = migratedDeltaWithEntity("minecraft:pig");

        assertTrue(OfflineMcaCisTranslator.matchesMigratedChunkShape(expected, matching)
                .valid());
        assertFalse(OfflineMcaCisTranslator.matchesMigratedChunkShape(expected, withEntityPayload)
                .valid());
    }

    @Test
    void omittableEmptyChunkRequiresNoBlocksBlockEntitiesOrEntities() {
        final ChunkDelta<BlockState, NbtCompound> empty = new ChunkDelta<>(BlockState::isAir);
        final ChunkDelta<BlockState, NbtCompound> entityOnly = new ChunkDelta<>(BlockState::isAir);
        entityOnly.addPendingEntity(new NbtCompound());

        assertTrue(OfflineMcaCisTranslator.isOmittableEmptyChunk(empty));
        assertFalse(OfflineMcaCisTranslator.isOmittableEmptyChunk(entityOnly));
    }

    @Test
    void retireRegionFileRenamesMcaToBackup() throws IOException {
        final Path mcaPath = tempDir.resolve("r.0.0.mca");
        Files.writeString(mcaPath, "chunkis");

        OfflineMcaCisTranslator.retireRegionFile(mcaPath);

        assertFalse(Files.exists(mcaPath));
        assertTrue(Files.exists(tempDir.resolve("r.0.0.mca.backup")));
    }

    private static ChunkDelta<BlockState, NbtCompound> migratedDelta() {
        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>(BlockState::isAir);

        final NbtCompound blockEntity = new NbtCompound();
        blockEntity.putString("id", "minecraft:chest");
        delta.addBlockEntityData(1, 70, 2, blockEntity);

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

    private static ChunkDelta<BlockState, NbtCompound> migratedDeltaWithEntity(final String entityId) {
        final ChunkDelta<BlockState, NbtCompound> delta = migratedDelta();
        final NbtCompound entity = new NbtCompound();
        entity.putString("id", entityId);
        delta.addPendingEntity(entity);
        return delta;
    }
}
