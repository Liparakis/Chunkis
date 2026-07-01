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
    /**
     * Bit shift mapping section-local Y into the packed palette-storage index.
     */
    private static final int SECTION_LOCAL_INDEX_Y_SHIFT = 8;
    /**
     * Bit shift mapping section-local Z into the packed palette-storage index.
     */
    private static final int SECTION_LOCAL_INDEX_Z_SHIFT = 4;
    /**
     * Optional reflective fast-path descriptor for direct PalettedContainer storage writes.
     */
    private static final ReflectionAccess PALETTED_CONTAINER_REFLECTION = resolveReflectionAccess();

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

    /**
     * Loads the nested vanilla runtime class that holds a PalettedContainer's active palette/storage pair.
     *
     * @return the nested {@code PalettedContainer$Data} class
     * @throws ClassNotFoundException if the current runtime layout no longer exposes that class
     */
    private static Class<?> loadPalettedContainerDataClass() throws ClassNotFoundException {
        return Class.forName("net.minecraft.world.chunk.PalettedContainer$Data");
    }

    /**
     * Resolves the direct-storage reflective fast path.
     *
     * <p>If any lookup fails, restore keeps working by falling back to the slower public
     * {@link PalettedContainer#swapUnsafe(int, int, int, Object)} path.</p>
     *
     * @return reflection descriptor, enabled only when all required fields were found
     */
    private static ReflectionAccess resolveReflectionAccess() {
        try {
            final Class<?> dataClass = loadPalettedContainerDataClass();
            return new ReflectionAccess(
                    true,
                    findField(PalettedContainer.class, "data", "field_34560", "b"),
                    findField(dataClass, "storage", "comp_118", "b"),
                    findField(dataClass, "palette", "comp_119", "c")
            );
        } catch (final ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn(
                    "Chunkis: PalettedContainer fast restore path disabled; falling back to swapUnsafe()",
                    e
            );
            return ReflectionAccess.disabled();
        }
    }

    /**
     * Finds one declared field using a list of candidate names across mapping namespaces.
     *
     * @param owner          class expected to declare the field
     * @param candidateNames ordered field names to try
     * @return the first matching field with accessibility forced on
     * @throws NoSuchFieldException when none of the candidates are present
     */
    private static Field findField(final Class<?> owner, final String... candidateNames) throws NoSuchFieldException {
        for (final String candidateName : candidateNames) {
            try {
                final Field field = owner.getDeclaredField(candidateName);
                field.setAccessible(true);
                return field;
            } catch (final NoSuchFieldException ignored) {
                // Try the next namespace name.
            }
        }
        throw new NoSuchFieldException(
                owner.getName() + " [" + String.join(", ", candidateNames) + "]"
        );
    }

    /**
     * Reads a previously resolved reflective field from a target object.
     *
     * @param field  field descriptor to read
     * @param target object holding that field
     * @return raw reflected field value
     * @throws IllegalStateException if reflective access unexpectedly fails
     */
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
     * @param sectionWriteCursor  reusable per-section write cursor for palette/storage access
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

    /**
     * Packs section-local coordinates into the linear raw storage index used by vanilla block-state containers.
     *
     * <p>The layout matches vanilla's section traversal order: {@code y -> z -> x}.</p>
     *
     * @param localX section-local X coordinate in {@code [0, 15]}
     * @param localY section-local Y coordinate in {@code [0, 15]}
     * @param localZ section-local Z coordinate in {@code [0, 15]}
     * @return packed palette-storage index in {@code [0, 4095]}
     */
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

        /**
         * Currently bound section index, or {@link Integer#MIN_VALUE} before the first bind.
         */
        private int sectionIndex = Integer.MIN_VALUE;
        /**
         * Currently bound block-state container receiving writes.
         */
        private PalettedContainer<BlockState> container;
        /**
         * Active raw palette storage of the bound container.
         */
        private PaletteStorage storage;
        /**
         * Active palette resolving block states to raw ids for the bound container.
         */
        private Palette<BlockState> palette;
        /**
         * Identity of the currently bound nested data holder used to detect palette/storage replacement.
         */
        private Object dataRef;
        /**
         * Per-section cache from block-state identity to resolved raw palette id.
         */
        private final IdentityHashMap<BlockState, Integer> paletteIds = new IdentityHashMap<>();
        /**
         * Count of block writes executed by this cursor.
         */
        private long writes;
        /**
         * Count of times this cursor bound to a different section.
         */
        private long rebinds;
        /**
         * Count of palette-id cache hits for the currently bound section.
         */
        private long paletteHits;
        /**
         * Count of palette-id cache misses for the currently bound section.
         */
        private long paletteMisses;
        /**
         * Count of palette cache clears caused by palette/storage replacement.
         */
        private long paletteInvalidations;

        /**
         * Writes one restored block into the target section.
         *
         * <p>Uses the reflective fast path when available; otherwise falls back to
         * {@link PalettedContainer#swapUnsafe(int, int, int, Object)}.</p>
         *
         * @param section            target chunk section
         * @param targetSectionIndex target section index inside the chunk section array
         * @param localX             section-local X coordinate
         * @param localY             section-local Y coordinate
         * @param localZ             section-local Z coordinate
         * @param state              restored block state
         */
        void write(
                final ChunkSection section,
                final int targetSectionIndex,
                final int localX,
                final int localY,
                final int localZ,
                final BlockState state
        ) {
            writes++;
            if (targetSectionIndex != sectionIndex) {
                bindSection(section, targetSectionIndex);
            }
            if (!PALETTED_CONTAINER_REFLECTION.available()) {
                container.swapUnsafe(localX, localY, localZ, state);
                return;
            }

            Integer paletteId = paletteIds.get(state);
            if (paletteId == null) {
                paletteMisses++;
                final Object before = dataRef;
                final int resolvedId = palette.index(state, container);
                refreshData();
                if (dataRef != before) {
                    paletteInvalidations++;
                    paletteIds.clear();
                }
                paletteIds.put(state, resolvedId);
                paletteId = resolvedId;
            } else {
                paletteHits++;
            }

            storage.set(toSectionLocalIndex(localX, localY, localZ), paletteId);
        }

        /**
         * Rebinds this cursor to a new section and refreshes cached container internals.
         *
         * @param section            target chunk section
         * @param targetSectionIndex section index within the chunk section array
         */
        private void bindSection(final ChunkSection section, final int targetSectionIndex) {
            rebinds++;
            sectionIndex = targetSectionIndex;
            container = section.getBlockStateContainer();
            paletteIds.clear();
            if (PALETTED_CONTAINER_REFLECTION.available()) {
                refreshData();
            }
        }

        /**
         * Returns a snapshot of accumulated cursor counters.
         */
        SectionWriteCursorStats snapshot() {
            return new SectionWriteCursorStats(
                    writes,
                    rebinds,
                    paletteHits,
                    paletteMisses,
                    paletteInvalidations
            );
        }

        /**
         * Refreshes reflective handles to the currently bound container's live data, storage, and palette.
         *
         * <p>Vanilla may replace the nested data object when the palette resizes, so callers refresh
         * after palette insertion to keep direct writes pointed at the current storage.</p>
         */
        @SuppressWarnings("unchecked")
        private void refreshData() {
            assert PALETTED_CONTAINER_REFLECTION.dataField() != null;
            dataRef = readField(PALETTED_CONTAINER_REFLECTION.dataField(), container);
            assert PALETTED_CONTAINER_REFLECTION.storageField() != null;
            storage = (PaletteStorage) readField(PALETTED_CONTAINER_REFLECTION.storageField(), dataRef);
            assert PALETTED_CONTAINER_REFLECTION.paletteField() != null;
            palette = (Palette<BlockState>) readField(PALETTED_CONTAINER_REFLECTION.paletteField(), dataRef);
        }
    }

    /**
     * Immutable descriptor for the optional reflective direct-write path.
     *
     * @param available    whether all reflective handles resolved successfully
     * @param dataField    field exposing the outer container's current nested data object
     * @param storageField field exposing the nested data object's raw storage
     * @param paletteField field exposing the nested data object's active palette
     */
    private record ReflectionAccess(
            boolean available,
            @Nullable Field dataField,
            @Nullable Field storageField,
            @Nullable Field paletteField
    ) {

        /**
         * Creates a disabled reflection descriptor used when the fast path is unavailable.
         *
         * @return disabled reflection descriptor
         */
        private static ReflectionAccess disabled() {
            return new ReflectionAccess(false, null, null, null);
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
