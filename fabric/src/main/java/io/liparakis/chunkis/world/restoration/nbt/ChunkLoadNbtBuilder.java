package io.liparakis.chunkis.world.restoration.nbt;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.Objects;

/**
 * Builds the synthetic chunk NBT that Chunkis feeds into vanilla load paths.
 */
final class ChunkLoadNbtBuilder {

    private ChunkLoadNbtBuilder() {
        throw new AssertionError("Utility class");
    }

    static CisNbtUtil.LoadChunkNbtResult buildLoadChunkNbt(
            final int chunkX,
            final int chunkZ,
            final int dataVersion,
            final ChunkDelta<?, NbtCompound> delta
    ) {
        final Object metadata = delta != null ? delta.getChunkMetadata() : null;
        final NbtCompound baseChunkNbt = CisNbtUtil.extractPersistedBaseChunkNbt(metadata);
        final boolean usePersistedBaseChunkForBlocks =
                CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(metadata);
        final CisNbtUtil.PersistedBaseChunkUsage baseChunkUsage;
        final NbtCompound root;

        if (baseChunkNbt != null && usePersistedBaseChunkForBlocks) {
            root = baseChunkNbt;
            baseChunkUsage = CisNbtUtil.PersistedBaseChunkUsage.USED;

            if (delta != null && delta.countNonNullEntities() > 0) {
                replaceChunkEntitiesFromDelta(root, castDelta(delta));
            }
        } else {
            root = CisNbtUtil.createBaseNbt(chunkX, chunkZ, dataVersion);
            baseChunkUsage = CisNbtUtil.hasPersistedBaseChunkNbt(metadata)
                    ? CisNbtUtil.PersistedBaseChunkUsage.SKIPPED
                    : CisNbtUtil.PersistedBaseChunkUsage.MISSING;
        }

        if (delta != null) {
            putChunkMetadata(root, castDelta(delta));
            CisNbtUtil.putDelta(root, castDelta(delta));
        }
        SyntheticChunkisLoadMarker.putLoadBaseChunkUsage(root, baseChunkUsage);

        return new CisNbtUtil.LoadChunkNbtResult(root, baseChunkUsage);
    }

    static void replaceChunkBlockEntitiesFromDelta(
            final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta
    ) {
        Objects.requireNonNull(root, "root");
        if (delta == null) {
            return;
        }

        final NbtList blockEntities = new NbtList();
        delta.getBlockEntities().long2ObjectEntrySet().forEach(entry -> {
            final NbtCompound nbt = entry.getValue();
            if (nbt != null) {
                blockEntities.add(nbt.copy());
            }
        });
        root.put(CisNbtUtil.BLOCK_ENTITIES_KEY, blockEntities);
    }

    static void replaceChunkEntitiesFromDelta(
            final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta
    ) {
        Objects.requireNonNull(root, "root");
        if (delta == null) {
            return;
        }

        final NbtList entities = new NbtList();
        delta.forEachEntity(nbt -> {
            if (nbt != null) {
                entities.add(nbt.copy());
            }
        });
        root.put("entities", entities);
    }

    static void putChunkMetadata(
            final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta
    ) {
        Objects.requireNonNull(root, "root");
        if (delta == null) {
            return;
        }

        final NbtCompound structures =
                CisNbtUtil.extractPersistedStructureMetadata(delta.getChunkMetadata());
        if (structures != null && !structures.isEmpty()) {
            root.put(CisNbtUtil.STRUCTURES_KEY, structures);
        }
    }

    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> castDelta(
            final ChunkDelta<?, NbtCompound> delta
    ) {
        return (ChunkDelta<BlockState, NbtCompound>) delta;
    }
}
