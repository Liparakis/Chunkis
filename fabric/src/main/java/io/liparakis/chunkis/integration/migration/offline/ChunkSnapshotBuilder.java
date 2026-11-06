package io.liparakis.chunkis.integration.migration.offline;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

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
    public static ChunkDelta<BlockState, CompoundTag> buildChunkDelta(final LevelHeightAccessor heightLimitView,
            final PalettedContainerFactory palettesFactory,
            final CompoundTag sourceNbt,
            final CompoundTag entitySourceNbt,
            final ChunkPos chunkPos) {
        final SerializableChunkData serialized = SerializableChunkData.parse(heightLimitView, palettesFactory, sourceNbt);
        if (serialized == null) {
            throw new IllegalStateException("Vanilla could not deserialize chunk " + chunkPos);
        }

        final ChunkDelta<BlockState, CompoundTag> delta = new ChunkDelta<>();
        final CompoundTag structureData = CisNbtUtil.extractStructureData(sourceNbt);
        final CompoundTag preservedAuxiliary = CisNbtUtil.extractPreservedAuxiliaryChunkNbtFromChunkRoot(sourceNbt);
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
    public static ChunkDelta<BlockState, CompoundTag> buildChunkDelta(final LevelHeightAccessor heightLimitView,
            final PalettedContainerFactory palettesFactory,
            final CompoundTag sourceNbt,
            final ChunkPos chunkPos) {
        return buildChunkDelta(heightLimitView, palettesFactory, sourceNbt, null, chunkPos);
    }

    /**
     * Captures all non-air block states from serialized chunk sections.
     *
     * @return {@code true} when at least one nether portal block was observed
     */
    private static boolean captureBlocks(final SerializableChunkData serialized,
            final ChunkDelta<BlockState, CompoundTag> delta) {
        boolean portalChunk = false;

        for (final SerializableChunkData.SectionData sectionData : serialized.sectionData()) {
            final LevelChunkSection section = sectionData.chunkSection();
            if (section == null || section.hasOnlyAir()) {
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
                        if (!portalChunk && state.is(Blocks.NETHER_PORTAL)) {
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
    private static void captureBlockEntities(final SerializableChunkData serialized,
            final CompoundTag sourceNbt,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, CompoundTag> delta) {
        final int chunkStartX = chunkPos.getMinBlockX();
        final int chunkStartZ = chunkPos.getMinBlockZ();

        for (final CompoundTag blockEntityNbt : serialized.blockEntities()) {
            addBlockEntityPayload(delta, chunkStartX, chunkStartZ, blockEntityNbt);
        }
        for (final CompoundTag blockEntityNbt : CisNbtUtil.extractCompoundList(chunkPayloadRoot(sourceNbt),
                "block_entities")) {
            addBlockEntityPayload(delta, chunkStartX, chunkStartZ, blockEntityNbt);
        }
    }

    /**
     * Adds one block-entity payload using local chunk coordinates.
     */
    private static void addBlockEntityPayload(final ChunkDelta<BlockState, CompoundTag> delta,
            final int chunkStartX,
            final int chunkStartZ,
            final CompoundTag blockEntityNbt) {
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
    private static void captureEntities(final SerializableChunkData serialized,
            final CompoundTag sourceNbt,
            final CompoundTag entitySourceNbt,
            final ChunkDelta<BlockState, CompoundTag> delta) {
        final Set<String> seen = new HashSet<>();

        for (final CompoundTag entityNbt : serialized.entities()) {
            addEntityPayload(delta, seen, entityNbt);
        }
        for (final CompoundTag entityNbt : extractEntityPayloads(chunkPayloadRoot(sourceNbt))) {
            addEntityPayload(delta, seen, entityNbt);
        }
        for (final CompoundTag entityNbt : extractEntityPayloads(chunkPayloadRoot(entitySourceNbt))) {
            addEntityPayload(delta, seen, entityNbt);
        }
    }

    /**
     * Captures one serialized chunk entity as a pending entity.
     */
    private static void addEntityPayload(final ChunkDelta<BlockState, CompoundTag> delta,
            final Set<String> seen,
            final CompoundTag entityNbt) {
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
    private static String entityPayloadKey(final CompoundTag entityNbt) {
        return entityNbt.getIntArray("UUID")
                .map(array -> "uuid:" + java.util.Arrays.toString(array))
                .orElseGet(() -> "nbt:" + entityNbt);
    }

    /**
     * Extracts entity payload compounds from modern or legacy root layouts.
     */
    static List<CompoundTag> extractEntityPayloads(final CompoundTag root) {
        final List<CompoundTag> payloads = CisNbtUtil.extractCompoundList(root, "Entities");
        return payloads.isEmpty() ? CisNbtUtil.extractCompoundList(root, "entities") : payloads;
    }

    /**
     * Returns the nested legacy {@code Level} payload root when present.
     */
    static CompoundTag chunkPayloadRoot(final CompoundTag root) {
        if (root == null) {
            return null;
        }
        return root.getCompound("Level")
                .orElse(root);
    }

    /**
     * Creates authoritative migrated metadata.
     */
    public static CompoundTag createMigratedChunkMetadata(final CompoundTag structureData,
            final CompoundTag preservedAuxiliaryChunkNbt,
            final boolean portalChunk) {
        final CompoundTag metadata = CisNbtUtil.createChunkMetadataTakingOwnership(structureData,
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
    static boolean isOmittableEmptyChunk(final ChunkDelta<BlockState, CompoundTag> delta) {
        return delta.getBlockChangesCount() == 0 && delta.getBlockEntities()
                .isEmpty() && delta.countPendingEntities() == 0;
    }

}
