package io.liparakis.chunkis.world.restoration.capture;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.ChunkSectionDebugUtil;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.SerializedChunk;
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
    public static ChunkDelta<BlockState, NbtCompound> capture(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> target,
            final String operationId
    ) {
        if (CisNbtUtil.hasFullBlockBaseline(target.getChunkMetadata()) && !target.isDirty()) {
            return target;
        }

        final int previousNonAirBlocks = countPersistedNonAirBlocks(target);
        final int liveNonAirBlocks = ChunkSectionDebugUtil.countNonAirBlocks(chunk);
        if (isSuspiciousBaselineShrink(previousNonAirBlocks, liveNonAirBlocks)) {
            final String restoreOperationId = chunk instanceof ChunkisDeltaDuck deltaDuck
                    ? deltaDuck.chunkis$getRestoreOperationId()
                    : null;
            Chunkis.LOGGER.warn(
                    "Chunkis: Rejected suspicious full snapshot for {} in {}"
                            + " previousNonAir={} liveNonAir={} suppressionCause={} restoreOperationId={} " +
                            "chunkStatus={} saveOperationId={}",
                    chunk.getPos(),
                    chunk.getWorld()
                            .getRegistryKey()
                            .getValue(),
                    previousNonAirBlocks,
                    liveNonAirBlocks,
                    PendingChunkMutationSuppression.currentCause(chunk),
                    restoreOperationId,
                    chunk.getStatus(),
                    operationId
            );
            return target;
        }
        PayloadWatchTracer.traceCapturedBlocks(chunk);
        target.clearBlockPayloads(false);
        target.clearBlockEntityPayloads(false);
        final NbtCompound fullChunkNbt = captureAuthoritativeBaseChunkNbt(chunk);

        final NbtCompound existingMetadata = target.getChunkMetadata();
        target.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        CisNbtUtil.extractPersistedStructureMetadata(existingMetadata),
                        true,
                        false,
                        fullChunkNbt,
                        BaseChunkCaptureUtil.hasPortalBlocks(chunk)
                ),
                false
        );
        target.setSuppressInitialRepopulation(true);
        return target;
    }

    /**
     * Evaluates if non-air block count has shrunk suspiciously.
     *
     * @param previousNonAirBlocks previous count
     * @param liveNonAirBlocks     current count
     * @return true if suspicious
     */
    static boolean isSuspiciousBaselineShrink(
            final int previousNonAirBlocks,
            final int liveNonAirBlocks
    ) {
        return previousNonAirBlocks > 0
                && liveNonAirBlocks >= 0
                && liveNonAirBlocks * 10 < previousNonAirBlocks * 6;
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

    /**
     * Serializes the live chunk into vanilla-compatible NBT that can be used directly as the
     * authoritative future load baseline.
     *
     * @param chunk live chunk being snapshotted
     * @return serialized full chunk NBT
     */
    private static NbtCompound captureAuthoritativeBaseChunkNbt(final WorldChunk chunk) {
        if (!(chunk.getWorld() instanceof ServerWorld world)) {
            throw new IllegalStateException("Full snapshot capture requires ServerWorld");
        }
        return SerializedChunk.fromChunk(world, chunk).serialize();
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
