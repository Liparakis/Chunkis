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
     * Status of a chunk that is fully generated; vanilla loads it as a finished chunk.
     */
    private static final String STATUS_FULL = "minecraft:full";

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

        if (CisNbtUtil.loadsAsFinishedChunk(metadata)) {
            // The stored baseline holds every block and the biomes are stored too: load a finished chunk instead
            // of regenerating terrain only to replace it. With its light stored too it loads lit, like a vanilla
            // chunk; otherwise vanilla lights the restored blocks itself.
            root.putString(CisNbtUtil.STATUS_KEY, STATUS_FULL);
            root.put(CisNbtUtil.SECTIONS_KEY, CisNbtUtil.getStoredSections(metadata));
            PendingTicksNbt.putInto((NbtCompound) metadata, root);
            // Only blocks exactly as stored: a dirty delta (rebuilt with newer edits, or unsaved) has others, and
            // vanilla rebuilds heightmaps the root lacks.
            if (delta != null && !delta.isDirty()) {
                ((NbtCompound) metadata).getCompound(CisNbtUtil.STORED_HEIGHTMAPS_KEY)
                        .ifPresent(heightmaps -> root.put(CisNbtUtil.HEIGHTMAPS_KEY, heightmaps.copy()));
            }
            if (CisNbtUtil.loadsWithStoredLight(metadata)) {
                root.putBoolean(CisNbtUtil.IS_LIGHT_ON_KEY, true);
            } else {
                root.remove(CisNbtUtil.IS_LIGHT_ON_KEY);
            }
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
