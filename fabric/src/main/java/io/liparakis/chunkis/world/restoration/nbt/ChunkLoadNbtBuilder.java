package io.liparakis.chunkis.world.restoration.nbt;

import io.liparakis.chunkis.core.ChunkDelta;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;

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
        final NbtCompound preservedAuxiliaryChunkNbt =
                CisNbtUtil.extractPreservedAuxiliaryChunkNbt(metadata);
        final boolean usePersistedBaseChunkForBlocks =
                CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(metadata);
        final boolean migratedAuthoritativeChunk =
                CisNbtUtil.isMigratedAuthoritativeChunk(metadata);
        final CisNbtUtil.PersistedBaseChunkUsage baseChunkUsage;
        final NbtCompound root;

        if (baseChunkNbt != null && usePersistedBaseChunkForBlocks) {
            root = baseChunkNbt;
            baseChunkUsage = CisNbtUtil.PersistedBaseChunkUsage.USED;
        } else {
            root = CisNbtUtil.createBaseNbt(chunkX, chunkZ, dataVersion);
            if (migratedAuthoritativeChunk && preservedAuxiliaryChunkNbt != null) {
                mergePreservedAuxiliaryMetadata(root, preservedAuxiliaryChunkNbt);
                root.putInt(CisNbtUtil.DATA_VERSION_KEY, dataVersion);
                root.putInt(CisNbtUtil.X_POS_KEY, chunkX);
                root.putInt(CisNbtUtil.Z_POS_KEY, chunkZ);
            }
            baseChunkUsage = CisNbtUtil.hasPersistedBaseChunkNbt(metadata)
                    ? CisNbtUtil.PersistedBaseChunkUsage.SKIPPED
                    : CisNbtUtil.PersistedBaseChunkUsage.MISSING;
        }

        if (delta != null) {
            // EntityReplayCoordinator owns CIS entity materialization; vanilla's embedded
            // entity loader would load the same payload a second time.
            root.remove("entities");
            putChunkMetadata(root, castDelta(delta));
            CisNbtUtil.putDelta(root, castDelta(delta));
        }
        SyntheticChunkisLoadMarker.putLoadBaseChunkUsage(root, baseChunkUsage);

        return new CisNbtUtil.LoadChunkNbtResult(root, baseChunkUsage);
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
     * Merges preserved auxiliary vanilla metadata into the synthetic load root.
     *
     * <p>Callers should apply explicit Chunkis-owned fields afterward so those
     * authoritative values win over the preserved payload.</p>
     *
     * @param root                  target synthetic root
     * @param preservedAuxiliaryNbt preserved opaque vanilla metadata
     */
    private static void mergePreservedAuxiliaryMetadata(
            final NbtCompound root,
            final NbtCompound preservedAuxiliaryNbt
    ) {
        root.copyFrom(preservedAuxiliaryNbt);
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
