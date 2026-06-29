package io.liparakis.chunkis.world.restoration.core;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.mixin.accessor.ChunkBlockEntityNbtAccessor;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Low-level block-grid mutation helpers used during chunk restore.
 *
 * <p>This is the unsafe part of restore: direct section writes, chunk reset to
 * air, and stale block-entity cleanup. Keeping it separate leaves
 * {@link ChunkRestorer} focused on restore transaction flow.</p>
 */
final class ChunkRestoreBlockOperations {

    /**
     * Logger instance reference.
     */
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Binary mask used to extract coordinate offsets within a chunk section.
     */
    private static final int SECTION_Y_MASK = 15;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkRestoreBlockOperations() {
        throw new AssertionError("Utility class");
    }

    /**
     * Clears every non-empty section in the target chunk back to air before snapshot replay.
     *
     * @param chunk chunk to reset
     */
    static void clearChunkToAir(final WorldChunk chunk) {
        final ChunkSection[] sections = chunk.getSectionArray();
        for (final ChunkSection section : sections) {
            if (section == null || section.isEmpty()) {
                continue;
            }

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        section.setBlockState(x, y, z, Blocks.AIR.getDefaultState());
                    }
                }
            }
        }

        for (final BlockPos pos : Set.copyOf(chunk.getBlockEntities()
                .keySet())) {
            removeStaleBlockEntityData(chunk, pos);
        }
        ((ChunkBlockEntityNbtAccessor) chunk).chunkis$getBlockEntityNbts()
                .clear();
    }

    /**
     * Applies one block state directly to a chunk section.
     *
     * @param chunk         chunk to mutate
     * @param chunkPosition chunk position, used for logging
     * @param localX        local chunk X coordinate
     * @param localY        absolute world Y coordinate
     * @param localZ        local chunk Z coordinate
     * @param state         state to write
     * @param worldPosition absolute world position, used for cleanup and logging
     * @param previousState pre-read block state when payload watches are active, otherwise {@code null}
     * @param sections      cached section array for the target chunk
     * @param bottomY       cached chunk bottom Y
     * @param topYInclusive cached chunk top Y inclusive
     * @param tracePayloadWatches whether payload-watch tracing is active for this restore pass
     * @param counters      failure counters to update
     * @param operationId   trace correlation ID
     * @return {@code true} if the block was applied
     */
    static boolean applyBlockChange(final WorldChunk chunk,
            final ChunkPos chunkPosition,
            final int localX,
            final int localY,
            final int localZ,
            final BlockState state,
            final BlockPos worldPosition,
            @Nullable final BlockState previousState,
            final ChunkSection[] sections,
            final int bottomY,
            final int topYInclusive,
            final boolean tracePayloadWatches,
            final FailureCounters counters,
            @Nullable final String operationId) {
        if (tracePayloadWatches) {
            PayloadWatchTracer.traceRestoreApplyAttempt(chunk,
                    worldPosition,
                    previousState,
                    state,
                    operationId,
                    "ChunkRestorer#applyBlockChange");
        }
        if (localY < bottomY || localY > topYInclusive) {
            counters.recordOutOfBoundsY();
            if (tracePayloadWatches) {
                PayloadWatchTracer.traceRestoreApplyFailed(chunk,
                        worldPosition,
                        previousState,
                        state,
                        operationId,
                        "ChunkRestorer#applyBlockChange",
                        "out-of-bounds-y");
            }
            LOGGER.warn("Skipping out-of-bounds restored block at {} in chunk {}", worldPosition, chunkPosition);
            return false;
        }

        try {
            final int sectionIndex = (localY - bottomY) >> 4;

            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                counters.recordInvalidSectionIndex();
                if (tracePayloadWatches) {
                    PayloadWatchTracer.traceRestoreApplyFailed(chunk,
                            worldPosition,
                            previousState,
                            state,
                            operationId,
                            "ChunkRestorer#applyBlockChange",
                            "invalid-section-index");
                }
                LOGGER.warn("Skipping restored block at {} in chunk {} with invalid section index {}",
                        worldPosition,
                        chunkPosition,
                        sectionIndex);
                return false;
            }

            final ChunkSection section = sections[sectionIndex];

            if (section == null) {
                counters.recordNullSection();
                if (tracePayloadWatches) {
                    PayloadWatchTracer.traceRestoreApplyFailed(chunk,
                            worldPosition,
                            previousState,
                            state,
                            operationId,
                            "ChunkRestorer#applyBlockChange",
                            "null-section");
                }
                LOGGER.warn("Skipping restored block at {} in chunk {} because section {} is null",
                        worldPosition,
                        chunkPosition,
                        sectionIndex);
                return false;
            }

            section.setBlockState(localX, localY & SECTION_Y_MASK, localZ, state);
            if (tracePayloadWatches) {
                PayloadWatchTracer.traceRestoreSetBlockReturned(chunk,
                        worldPosition,
                        previousState,
                        state,
                        operationId,
                        "ChunkRestorer#applyBlockChange");
                PayloadWatchTracer.traceRestoreStateAfterSetBlock(chunk,
                        worldPosition,
                        previousState,
                        state,
                        operationId,
                        "ChunkRestorer#applyBlockChange");
            }

            if (!state.hasBlockEntity()) {
                removeStaleBlockEntityData(chunk, worldPosition);
            }

            return true;
        } catch (final Exception e) {
            counters.recordException();
            if (tracePayloadWatches) {
                PayloadWatchTracer.traceRestoreApplyFailed(chunk,
                        worldPosition,
                        previousState,
                        state,
                        operationId,
                        "ChunkRestorer#applyBlockChange",
                        "exception");
            }
            LOGGER.error("Failed to restore block at {} in chunk {}", worldPosition, chunkPosition, e);
            return false;
        }
    }

    /**
     * Prunes and deletes stale block entity entries at target coordinates.
     *
     * @param chunk         target world chunk
     * @param worldPosition absolute coordinates pos
     */
    private static void removeStaleBlockEntityData(final WorldChunk chunk, final BlockPos worldPosition) {
        removePendingBlockEntityNbt(chunk, worldPosition);
        removeLiveBlockEntity(chunk, worldPosition);
    }

    /**
     * Prunes scheduled pending NBT payloads.
     *
     * @param chunk         target world chunk
     * @param worldPosition absolute coordinates pos
     */
    private static void removePendingBlockEntityNbt(final WorldChunk chunk, final BlockPos worldPosition) {
        ((ChunkBlockEntityNbtAccessor) chunk).chunkis$getBlockEntityNbts()
                .remove(worldPosition);
    }

    /**
     * Discards active live block entity instances.
     *
     * @param chunk         target world chunk
     * @param worldPosition absolute coordinates pos
     */
    private static void removeLiveBlockEntity(final WorldChunk chunk, final BlockPos worldPosition) {
        chunk.getBlockEntities()
                .remove(worldPosition);
        chunk.removeBlockEntity(worldPosition);
    }

    /**
     * Mutable counters for low-level restore block application failures.
     */
    static class FailureCounters {

        /**
         * Count of instructions evaluated.
         */
        private int visitedInstructions;

        /**
         * Count of blocks successfully applied.
         */
        private int appliedBlocks;

        /**
         * Count of null states encountered.
         */
        private int nullState;

        /**
         * Count of coordinates outside Y boundaries.
         */
        private int outOfBoundsY;

        /**
         * Count of invalid section index resolves.
         */
        private int invalidSectionIndex;

        /**
         * Count of missing section instances.
         */
        private int nullSection;

        /**
         * Count of exceptions occurred.
         */
        private int exception;

        /**
         * Default constructor.
         */
        FailureCounters() {
        }

        /**
         * Records visited instruction event.
         */
        void recordVisitedInstruction() {
            visitedInstructions++;
        }

        /**
         * Records successfully applied block event.
         */
        void recordAppliedBlock() {
            appliedBlocks++;
        }

        /**
         * Records null block state event.
         */
        void recordNullState() {
            nullState++;
        }

        /**
         * Records out of bounds coordinates event.
         */
        void recordOutOfBoundsY() {
            outOfBoundsY++;
        }

        /**
         * Records invalid section index event.
         */
        void recordInvalidSectionIndex() {
            invalidSectionIndex++;
        }

        /**
         * Records null section event.
         */
        void recordNullSection() {
            nullSection++;
        }

        /**
         * Records serialization exception event.
         */
        void recordException() {
            exception++;
        }

        /**
         * Summarizes counts inside description text string.
         *
         * @return description summary text
         */
        String describe() {
            return "visited=" + visitedInstructions + ", applied=" + appliedBlocks + ", nullState=" + nullState
                    + ", outOfBoundsY=" + outOfBoundsY + ", invalidSectionIndex=" + invalidSectionIndex
                    + ", nullSection=" + nullSection + ", exception=" + exception;
        }
    }
}
