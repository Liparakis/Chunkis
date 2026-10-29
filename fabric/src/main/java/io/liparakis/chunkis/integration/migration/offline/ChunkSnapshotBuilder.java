package io.liparakis.chunkis.integration.migration.offline;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.chunk.SerializedChunk;

/**
 * Builds an authoritative CIS chunk snapshot from serialized vanilla (MCA) chunk NBT.
 * 
 * <p>Shared by the offline migration and the MCA debug commands.</p>
 */
public final class ChunkSnapshotBuilder {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkSnapshotBuilder() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures one authoritative full CIS snapshot from raw serialized chunk
     * data.
     */
    public static ChunkDelta<BlockState, NbtCompound> buildChunkDelta(final HeightLimitView heightLimitView,
            final PalettesFactory palettesFactory,
            final NbtCompound sourceNbt,
            final NbtCompound entitySourceNbt,
            final ChunkPos chunkPos) {
        final SerializedChunk serialized = SerializedChunk.fromNbt(heightLimitView, palettesFactory, sourceNbt);
        if (serialized == null) {
            throw new IllegalStateException("Vanilla could not deserialize chunk " + chunkPos);
        }

        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();
        final NbtCompound structureData = CisNbtUtil.extractStructureData(sourceNbt);
        final NbtCompound preservedAuxiliary = CisNbtUtil.extractPreservedAuxiliaryChunkNbtFromChunkRoot(sourceNbt);
        final boolean portalChunk = captureBlocks(serialized, delta);

        delta.setSuppressInitialRepopulation(true);
        delta.setChunkMetadata(createMigratedChunkMetadata(structureData, preservedAuxiliary, portalChunk), false);
        captureBlockEntities(serialized, sourceNbt, chunkPos, delta);
        captureEntities(serialized, sourceNbt, entitySourceNbt, delta);
        return delta;
    }

    /**
     * Compatibility overload for callers that only have chunk-region NBT.
     */
    public static ChunkDelta<BlockState, NbtCompound> buildChunkDelta(final HeightLimitView heightLimitView,
            final PalettesFactory palettesFactory,
            final NbtCompound sourceNbt,
            final ChunkPos chunkPos) {
        return buildChunkDelta(heightLimitView, palettesFactory, sourceNbt, null, chunkPos);
    }

    /**
     * Captures all non-air block states from serialized chunk sections.
     *
     * @return {@code true} when at least one nether portal block was observed
     */
    private static boolean captureBlocks(final SerializedChunk serialized,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        boolean portalChunk = false;

        for (final SerializedChunk.SectionData sectionData : serialized.sectionData()) {
            final ChunkSection section = sectionData.chunkSection();
            if (section == null || section.isEmpty()) {
                continue;
            }

            final int sectionBottomY = sectionData.y() * 16;
            for (int localY = 0; localY < 16; localY++) {
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        final BlockState state = section.getBlockState(localX, localY, localZ);
                        if (state == null || state.isAir()) {
                            continue;
                        }

                        delta.addBlockChange(localX, sectionBottomY + localY, localZ, state);
                        if (!portalChunk && state.isOf(Blocks.NETHER_PORTAL)) {
                            portalChunk = true;
                        }
                    }
                }
            }
        }

        return portalChunk;
    }

    /**
     * Captures serialized block entities using local chunk X/Z coordinates.
     */
    private static void captureBlockEntities(final SerializedChunk serialized,
            final NbtCompound sourceNbt,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final int chunkStartX = chunkPos.getStartX();
        final int chunkStartZ = chunkPos.getStartZ();

        for (final NbtCompound blockEntityNbt : serialized.blockEntities()) {
            addBlockEntityPayload(delta, chunkStartX, chunkStartZ, blockEntityNbt);
        }
        for (final NbtCompound blockEntityNbt : CisNbtUtil.extractCompoundList(chunkPayloadRoot(sourceNbt),
                "block_entities")) {
            addBlockEntityPayload(delta, chunkStartX, chunkStartZ, blockEntityNbt);
        }
    }

    /**
     * Adds one block-entity payload using local chunk coordinates.
     */
    private static void addBlockEntityPayload(final ChunkDelta<BlockState, NbtCompound> delta,
            final int chunkStartX,
            final int chunkStartZ,
            final NbtCompound blockEntityNbt) {
        if (blockEntityNbt == null || !blockEntityNbt.contains("id")) {
            throw new IllegalStateException("Block entity payload is missing required id field");
        }

        final int worldX = blockEntityNbt.getInt("x")
                .orElseThrow();
        final int worldY = blockEntityNbt.getInt("y")
                .orElseThrow();
        final int worldZ = blockEntityNbt.getInt("z")
                .orElseThrow();
        delta.addBlockEntityData(worldX - chunkStartX, worldY, worldZ - chunkStartZ, blockEntityNbt.copy());
    }

    /**
     * Captures serialized chunk entities from both inline and external entity storage.
     */
    private static void captureEntities(final SerializedChunk serialized,
            final NbtCompound sourceNbt,
            final NbtCompound entitySourceNbt,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final Set<String> seen = new HashSet<>();

        for (final NbtCompound entityNbt : serialized.entities()) {
            addEntityPayload(delta, seen, entityNbt);
        }
        for (final NbtCompound entityNbt : extractEntityPayloads(chunkPayloadRoot(sourceNbt))) {
            addEntityPayload(delta, seen, entityNbt);
        }
        for (final NbtCompound entityNbt : extractEntityPayloads(chunkPayloadRoot(entitySourceNbt))) {
            addEntityPayload(delta, seen, entityNbt);
        }
    }

    /**
     * Captures one serialized chunk entity as a pending entity.
     */
    private static void addEntityPayload(final ChunkDelta<BlockState, NbtCompound> delta,
            final Set<String> seen,
            final NbtCompound entityNbt) {
        if (entityNbt == null) {
            return;
        }
        final String key = entityPayloadKey(entityNbt);
        if (!seen.add(key)) {
            return;
        }
        delta.addPendingEntity(entityNbt.copy());
    }

    /**
     * Produces a stable dedupe key for migrated entity payloads.
     */
    private static String entityPayloadKey(final NbtCompound entityNbt) {
        return entityNbt.getIntArray("UUID")
                .map(array -> "uuid:" + java.util.Arrays.toString(array))
                .orElseGet(() -> "nbt:" + entityNbt);
    }

    /**
     * Extracts entity payload compounds from modern or legacy root layouts.
     */
    static List<NbtCompound> extractEntityPayloads(final NbtCompound root) {
        final List<NbtCompound> payloads = CisNbtUtil.extractCompoundList(root, "Entities");
        return payloads.isEmpty() ? CisNbtUtil.extractCompoundList(root, "entities") : payloads;
    }

    /**
     * Returns the nested legacy {@code Level} payload root when present.
     */
    static NbtCompound chunkPayloadRoot(final NbtCompound root) {
        if (root == null) {
            return null;
        }
        return root.getCompound("Level")
                .orElse(root);
    }

    /**
     * Creates authoritative migrated metadata.
     */
    public static NbtCompound createMigratedChunkMetadata(final NbtCompound structureData,
            final NbtCompound preservedAuxiliaryChunkNbt,
            final boolean portalChunk) {
        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(structureData,
                true,
                true,
                null,
                portalChunk);
        CisNbtUtil.putPreservedAuxiliaryChunkNbt(metadata, preservedAuxiliaryChunkNbt);
        CisNbtUtil.markMigratedAuthoritativeChunk(metadata);
        return metadata;
    }

    /**
     * Returns whether a translated chunk record is an empty placeholder that
     * should be omitted entirely so future loads treat it as absent.
     */
    static boolean isOmittableEmptyChunk(final ChunkDelta<BlockState, NbtCompound> delta) {
        return delta.getBlockChangesCount() == 0 && delta.getBlockEntities()
                .isEmpty() && delta.countPendingEntities() == 0;
    }

}
