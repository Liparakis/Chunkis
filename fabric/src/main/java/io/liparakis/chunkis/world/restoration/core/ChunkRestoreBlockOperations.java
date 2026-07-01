package io.liparakis.chunkis.world.restoration.core;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.mixin.accessor.ChunkBlockEntityNbtAccessor;
import java.util.IdentityHashMap;
import java.util.Set;
import java.lang.reflect.Field;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PaletteProvider;
import net.minecraft.world.chunk.PalettedContainer;
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
    private static final int SECTION_LOCAL_INDEX_Y_SHIFT = 8;
    private static final int SECTION_LOCAL_INDEX_Z_SHIFT = 4;
    private static final Field PALETTED_CONTAINER_DATA_FIELD = findField(
            PalettedContainer.class,
            "data",
            "field_34560",
            "b"
    );
    private static final Field PALETTED_CONTAINER_DATA_STORAGE_FIELD = findField(
            loadPalettedContainerDataClass(),
            "storage",
            "comp_118",
            "b"
    );
    private static final Field PALETTED_CONTAINER_DATA_PALETTE_FIELD = findField(
            loadPalettedContainerDataClass(),
            "palette",
            "comp_119",
            "c"
    );

    /**
     * Shared block-state palette provider used to build fresh air-only section containers.
     *
     * <p>Restore always runs on the server thread, so we can safely construct a new
     * mutable container per section while reusing the immutable provider definition.</p>
     */
    private static final PaletteProvider<BlockState> BLOCK_STATE_PALETTE_PROVIDER =
            PaletteProvider.forBlockStates(Block.STATE_IDS);

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
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            final ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) {
                continue;
            }
            // Replacing the whole section is substantially cheaper than touching 4096 cells one by one.
            sections[sectionIndex] = createAirSectionPreservingBiomes(section);
        }

        for (final BlockPos pos : Set.copyOf(chunk.getBlockEntities()
                .keySet())) {
            removeStaleBlockEntityData(chunk, pos);
        }
        ((ChunkBlockEntityNbtAccessor) chunk).chunkis$getBlockEntityNbts()
                .clear();
    }

    /**
     * Creates a new air-only section while retaining the generated biome container.
     *
     * <p>Biome data must survive restore clears because sparse replay only resets block
     * contents. Reusing the prior biome container avoids a full biome repopulation pass.</p>
     *
     * @param section section being replaced
     * @return fresh air-backed section with original biome data
     */
    private static ChunkSection createAirSectionPreservingBiomes(final ChunkSection section) {
        return new ChunkSection(
                new PalettedContainer<>(Blocks.AIR.getDefaultState(), BLOCK_STATE_PALETTE_PROVIDER),
                section.getBiomeContainer()
        );
    }

    private static Class<?> loadPalettedContainerDataClass() {
        try {
            return Class.forName("net.minecraft.world.chunk.PalettedContainer$Data");
        } catch (final ClassNotFoundException e) {
            throw new IllegalStateException("Chunkis: could not load PalettedContainer$Data", e);
        }
    }

    private static Field findField(final Class<?> owner, final String... candidateNames) {
        for (final String candidateName : candidateNames) {
            try {
                final Field field = owner.getDeclaredField(candidateName);
                field.setAccessible(true);
                return field;
            } catch (final NoSuchFieldException ignored) {
                // Try the next namespace name.
            }
        }
        throw new IllegalStateException(
                "Chunkis: could not resolve field on " + owner.getName() + " from candidates " + String.join(", ",
                        candidateNames)
        );
    }

    private static Object readField(final Field field, final Object target) {
        try {
            return field.get(target);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException("Chunkis: failed reading field " + field.getName(), e);
        }
    }

    /**
     * Applies one block state directly to a chunk section.
     *
     * @param chunk               chunk to mutate
     * @param chunkPosition       chunk position, used for logging
     * @param localY              absolute world Y coordinate
     * @param state               state to write
     * @param worldPosition       absolute world position, used for cleanup and logging
     * @param previousState       pre-read block state when payload watches are active, otherwise {@code null}
     * @param sections            cached section array for the target chunk
     * @param bottomY             cached chunk bottom Y
     * @param topYInclusive       cached chunk top Y inclusive
     * @param tracePayloadWatches whether payload-watch tracing is active for this restore pass
     * @param clearedToAir        whether the chunk was pre-cleared to air before replay
     * @param counters            failure counters to update
     * @param operationId         trace correlation ID
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
            final boolean clearedToAir,
            final SectionWriteCursor sectionWriteCursor,
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

            // ponytail: resolve section palette ids once per distinct state instead of once per block write.
            sectionWriteCursor.write(section, sectionIndex, localX, localY & SECTION_Y_MASK, localZ, state);
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

            if (!clearedToAir && !state.hasBlockEntity()) {
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

    static int toSectionLocalIndex(final int localX, final int localY, final int localZ) {
        return (localY << SECTION_LOCAL_INDEX_Y_SHIFT) | (localZ << SECTION_LOCAL_INDEX_Z_SHIFT) | localX;
    }

    /**
     * Reuses per-section palette lookups during restore-time raw block writes.
     *
     * <p>{@link PalettedContainer#swapUnsafe(int, int, int, Object)} still asks the section
     * palette to resolve the raw id for every single block. Restore replays many repeated
     * states per section, so caching those raw ids once per distinct state cuts the hot
     * path down to a storage write.</p>
     */
    static final class SectionWriteCursor {

        private int sectionIndex = Integer.MIN_VALUE;
        private PalettedContainer<BlockState> container;
        private PaletteStorage storage;
        private Palette<BlockState> palette;
        private Object dataRef;
        private final IdentityHashMap<BlockState, Integer> paletteIds = new IdentityHashMap<>();

        void write(
                final ChunkSection section,
                final int targetSectionIndex,
                final int localX,
                final int localY,
                final int localZ,
                final BlockState state
        ) {
            if (targetSectionIndex != sectionIndex) {
                bindSection(section, targetSectionIndex);
            }

            Integer paletteId = paletteIds.get(state);
            if (paletteId == null) {
                final Object before = dataRef;
                final int resolvedId = palette.index(state, container);
                refreshData();
                if (dataRef != before) {
                    paletteIds.clear();
                }
                paletteIds.put(state, resolvedId);
                paletteId = resolvedId;
            }

            storage.set(toSectionLocalIndex(localX, localY, localZ), paletteId);
        }

        private void bindSection(final ChunkSection section, final int targetSectionIndex) {
            sectionIndex = targetSectionIndex;
            container = section.getBlockStateContainer();
            paletteIds.clear();
            refreshData();
        }

        @SuppressWarnings("unchecked")
        private void refreshData() {
            dataRef = readField(PALETTED_CONTAINER_DATA_FIELD, container);
            storage = (PaletteStorage) readField(PALETTED_CONTAINER_DATA_STORAGE_FIELD, dataRef);
            palette = (Palette<BlockState>) readField(PALETTED_CONTAINER_DATA_PALETTE_FIELD, dataRef);
        }
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
