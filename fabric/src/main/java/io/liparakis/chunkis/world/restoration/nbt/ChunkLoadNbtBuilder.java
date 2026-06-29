package io.liparakis.chunkis.world.restoration.nbt;

import io.liparakis.chunkis.core.ChunkDelta;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/**
 * Builds the synthetic chunk NBT that Chunkis feeds into vanilla load paths.
 */
final class ChunkLoadNbtBuilder {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkLoadNbtBuilder() {
        throw new AssertionError("Utility class");
    }

    /**
     * Synthesizes the final chunk load NBT compound based on delta metrics.
     *
     * @param chunkX      chunk X coordinate
     * @param chunkZ      chunk Z coordinate
     * @param dataVersion data version integer mapping
     * @param delta       source block delta
     * @return LoadChunkNbtResult wrapper
     */
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

            if (delta.countNonNullEntities() > 0) {
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

    /**
     * Replaces the entities list key inside target root NBT with elements fetched from delta.
     *
     * @param root  target NBT compound
     * @param delta source block delta
     */
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

    /**
     * Attaches structural metadata from delta to target root NBT.
     *
     * @param root  target NBT compound
     * @param delta source block delta
     */
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

    /**
     * Casts delta parameters safely.
     *
     * @param delta target delta to cast
     * @return casted delta mapping
     */
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> castDelta(
            final ChunkDelta<?, NbtCompound> delta
    ) {
        return (ChunkDelta<BlockState, NbtCompound>) delta;
    }
}
