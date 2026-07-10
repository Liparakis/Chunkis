package io.liparakis.chunkis.world.restoration.capture;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.ChunkSectionDebugUtil;
import io.liparakis.chunkis.mixin.accessor.ChunkSectionAccessor;
import io.liparakis.chunkis.mixin.accessor.PalettedContainerAccessor;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.IdentityHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Captures an authoritative compact CIS snapshot from a live chunk.
 */
public final class CisSnapshotCapture {

    /**
     * Width/height/depth of one vanilla chunk section in blocks.
     */
    private static final int SECTION_SIZE = 16;

    /**
     * Bit shift used to convert a section index into its world-space Y offset.
     */
    private static final int SECTION_SHIFT = 4;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private CisSnapshotCapture() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures blocks and block entities from a live chunk into target delta.
     *
     * @param chunk       source live world chunk
     * @param target      destination block delta
     * @param operationId active load/save operation ID
     * @return updated block delta
     */
    public static ChunkDelta<BlockState, NbtCompound> capture(final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> target,
            final String operationId) {
        if (CisNbtUtil.hasFullBlockBaseline(target.getChunkMetadata()) && !target.isDirty()) {
            return target;
        }

        final int previousNonAirBlocks = countPersistedNonAirBlocks(target);
        final int liveNonAirBlocks = ChunkSectionDebugUtil.countNonAirBlocks(chunk);
        if (isSuspiciousBaselineShrink(previousNonAirBlocks, liveNonAirBlocks)) {
            final String restoreOperationId =
                    chunk instanceof ChunkisDeltaDuck deltaDuck ? deltaDuck.chunkis$getRestoreOperationId() : null;
            Chunkis.LOGGER.warn("Chunkis: Rejected suspicious full snapshot for {} in {}"
                            + " previousNonAir={} liveNonAir={} suppressionCause={} restoreOperationId={} "
                            + "chunkStatus={} saveOperationId={}",
                    chunk.getPos(),
                    chunk.getWorld()
                            .getRegistryKey()
                            .getValue(),
                    previousNonAirBlocks,
                    liveNonAirBlocks,
                    PendingChunkMutationSuppression.currentCause(chunk),
                    restoreOperationId,
                    chunk.getStatus(),
                    operationId);
            return target;
        }
        PayloadWatchTracer.traceCapturedBlocks(chunk);
        if (!CisNbtUtil.hasFullBlockBaseline(target.getChunkMetadata())
                && shouldPersistBaseChunkForSnapshot(chunk.getBlockEntities()
                .size())) {
            final NbtCompound fullChunkNbt = BaseChunkCaptureUtil.captureBaseChunk((net.minecraft.server.world.ServerWorld) chunk.getWorld(),
                            chunk,
                            target,
                            BaseChunkCaptureUtil.hasPortalBlocks(chunk))
                    .getChunkMetadata();
            target.setChunkMetadata(fullChunkNbt, false);
            target.setSuppressInitialRepopulation(true);
            return target;
        }
        final boolean preserveMigratedBlockEntities = shouldPreserveMigratedBlockEntities(target,
                chunk.getBlockEntities()
                        .isEmpty());
        final Long2ObjectMap<NbtCompound> preservedBlockEntities =
                preserveMigratedBlockEntities ? new Long2ObjectOpenHashMap<>(target.getBlockEntities()) : null;
        target.clearBlockPayloads(false);
        if (preservedBlockEntities != null) {
            restoreBlockEntities(target, preservedBlockEntities);
        }
        captureAuthoritativeBlockBaseline(chunk, target);
        ChunkBlockEntityCapture.captureBlockEntities(chunk,
                chunk.getWorld()
                        .getRegistryManager(),
                target);

        final NbtCompound existingMetadata = target.getChunkMetadata();
        target.setChunkMetadata(createAuthoritativeSnapshotMetadata(existingMetadata,
                BaseChunkCaptureUtil.hasPortalBlocks(chunk)), false);
        target.setSuppressInitialRepopulation(true);
        return target;
    }

    static boolean shouldPersistBaseChunkForSnapshot(final int blockEntityCount) {
        return blockEntityCount > 0;
    }

    /**
     * Keeps migrated sparse block-entity payloads when vanilla has not instantiated
     * any live block entities yet.
     */
    static boolean shouldPreserveMigratedBlockEntities(final ChunkDelta<BlockState, NbtCompound> target,
            final boolean noLiveBlockEntities) {
        return noLiveBlockEntities && target != null && !target.getBlockEntities()
                .isEmpty() && CisNbtUtil.isMigratedAuthoritativeChunk(target.getChunkMetadata());
    }

    static void restoreBlockEntities(final ChunkDelta<BlockState, NbtCompound> target,
            final Long2ObjectMap<NbtCompound> blockEntities) {
        blockEntities.forEach((packedPos, nbt) -> {
            if (nbt == null) {
                return;
            }
            target.addBlockEntityData(io.liparakis.chunkis.core.BlockInstruction.unpackX(packedPos),
                    io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos),
                    io.liparakis.chunkis.core.BlockInstruction.unpackZ(packedPos),
                    nbt,
                    false);
        });
    }

    /**
     * Evaluates if non-air block count has shrunk suspiciously.
     *
     * @param previousNonAirBlocks previous count
     * @param liveNonAirBlocks     current count
     * @return true if suspicious
     */
    static boolean isSuspiciousBaselineShrink(final int previousNonAirBlocks, final int liveNonAirBlocks) {
        return previousNonAirBlocks > 0 && liveNonAirBlocks >= 0 && liveNonAirBlocks * 10 < previousNonAirBlocks * 6;
    }

    /**
     * Counts persisted non-air blocks in target delta.
     *
     * @param target block delta instance
     * @return non-air block count
     */
    private static int countPersistedNonAirBlocks(final ChunkDelta<BlockState, NbtCompound> target) {
        if (target == null || !CisNbtUtil.hasFullBlockBaseline(target.getChunkMetadata())) {
            return 0;
        }
        return target.getBlockChangesCount();
    }

    static NbtCompound createAuthoritativeSnapshotMetadata(final NbtCompound existingMetadata,
            final boolean portalChunk) {
        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(CisNbtUtil.extractPersistedStructureMetadata(
                existingMetadata), true, true, null, portalChunk);
        CisNbtUtil.preserveMigratedAuthoritativeMetadata(existingMetadata, metadata);
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private static void captureAuthoritativeBlockBaseline(final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> target) {
        final ChunkSection[] sections = chunk.getSectionArray();
        final int chunkBottomY = chunk.getBottomY();
        target.ensureBlockCapacity(countNonAirBlocks(sections));
        final IdentityHashMap<BlockState, Integer> paletteIds = new IdentityHashMap<>();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            final ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) {
                continue;
            }

            final PalettedContainer<BlockState> container = section.getBlockStateContainer();
            final PalettedContainerAccessor<BlockState> rawContainer = (PalettedContainerAccessor<BlockState>) container;
            for (int index = 0; index < SECTION_SIZE * SECTION_SIZE * SECTION_SIZE; index++) {
                final BlockState state = rawContainer.chunkis$get(index);
                if (state.isAir()) {
                    continue;
                }
                final Integer cachedPaletteId = paletteIds.get(state);
                final int paletteId;
                if (cachedPaletteId != null) {
                    paletteId = cachedPaletteId;
                } else {
                    paletteId = target.getBlockPalette()
                            .getOrAdd(state);
                    paletteIds.put(state, paletteId);
                }
                final int localX = index & (SECTION_SIZE - 1);
                final int localZ = (index >>> 4) & (SECTION_SIZE - 1);
                final int localY = index >>> 8;
                target.appendDecodedBlockFast(localX,
                        toWorldY(chunkBottomY, sectionIndex, localY),
                        localZ,
                        paletteId);
            }
        }
    }

    private static int countNonAirBlocks(final ChunkSection[] sections) {
        int count = 0;
        for (final ChunkSection section : sections) {
            if (section == null || section.isEmpty()) {
                continue;
            }
            count += ((ChunkSectionAccessor) section).chunkis$getNonEmptyBlockCount();
        }
        return count;
    }

    /**
     * Converts a section-local Y coordinate into an absolute chunk-local world Y.
     *
     * <p>Retained as a package-visible helper for the existing unit test that locks down
     * chunk-section coordinate translation.</p>
     *
     * @param chunkBottomY bottom Y coordinates of the chunk
     * @param sectionIndex chunk section index
     * @param localY       local Y offset
     * @return absolute world Y coordinate
     */
    static int toWorldY(final int chunkBottomY, final int sectionIndex, final int localY) {
        return chunkBottomY + (sectionIndex << SECTION_SHIFT) + localY;
    }
}
