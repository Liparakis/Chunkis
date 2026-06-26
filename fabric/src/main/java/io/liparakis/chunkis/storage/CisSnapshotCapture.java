package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.world.ChunkBlockEntityCapture;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Captures an authoritative compact CIS snapshot from a live chunk.
 */
public final class CisSnapshotCapture {

    /** Width/height/depth of one vanilla chunk section in blocks. */
    private static final int SECTION_SIZE = 16;
    /** Bit shift used to convert a section index into its world-space Y offset. */
    private static final int SECTION_SHIFT = 4;

    private CisSnapshotCapture() {
        throw new AssertionError("Utility class");
    }

    /**
     * Rebuilds {@code target} as a full authoritative snapshot of {@code chunk}.
     *
     * <p>Only non-air blocks are written because air is implicit in CIS snapshot
     * restore. Existing block and block-entity payloads on {@code target} are
     * cleared first so the result is a fresh snapshot, not a merge.</p>
     *
     * @param chunk  live world chunk to snapshot
     * @param target reusable delta that receives the snapshot payload
     * @return {@code target}, for call chaining
     */
    public static ChunkDelta<BlockState, NbtCompound> capture(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> target
    ) {
        PayloadWatchTracer.traceCapturedBlocks(chunk);
        target.clearBlockPayloads(false);
        target.clearBlockEntityPayloads(false);

        final ChunkSection[] sections = chunk.getSectionArray();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            final ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) {
                continue;
            }

            for (int localY = 0; localY < SECTION_SIZE; localY++) {
                final int worldY = toWorldY(chunk.getBottomY(), sectionIndex, localY);
                for (int localZ = 0; localZ < SECTION_SIZE; localZ++) {
                    for (int localX = 0; localX < SECTION_SIZE; localX++) {
                        final BlockState state = section.getBlockState(localX, localY, localZ);
                        if (!state.isAir()) {
                            target.addBlockChange(localX, worldY, localZ, state, false);
                        }
                    }
                }
            }
        }

        ChunkBlockEntityCapture.captureBlockEntities(
                chunk,
                chunk.getWorld().getRegistryManager(),
                target
        );

        final Object existingMetadata = target.getChunkMetadata();
        target.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        CisNbtUtil.extractPersistedStructureMetadata((NbtCompound) existingMetadata),
                        true,
                        true,
                        CisNbtUtil.extractPersistedBaseChunkNbt(existingMetadata),
                        BaseChunkCaptureUtil.hasPortalBlocks(chunk)
                ),
                false
        );
        target.setSuppressInitialRepopulation(true);
        return target;
    }

    /**
     * Converts a section-local Y coordinate into an absolute chunk-local world Y.
     */
    static int toWorldY(final int chunkBottomY, final int sectionIndex, final int localY) {
        return chunkBottomY + (sectionIndex << SECTION_SHIFT) + localY;
    }
}
