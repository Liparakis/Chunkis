package io.liparakis.chunkis.world.restoration.capture;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.ChunkSectionDebugUtil;
import io.liparakis.chunkis.mixin.accessor.ChunkSectionAccessor;
import io.liparakis.chunkis.mixin.accessor.PalettedContainerAccessor;
import io.liparakis.chunkis.world.restoration.core.PalettedContainerFields;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.restoration.nbt.PendingTicksNbt;
import io.liparakis.chunkis.world.restoration.nbt.StoredSections;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.IdentityHashMap;

/**
 * Captures an authoritative compact CIS snapshot from a live chunk.
 */
public final class CisSnapshotCapture {

    /**
     * Width/height/depth of one vanilla chunk section in blocks.
     */
    private static final int SECTION_SIZE = 16;

    /**
     * Per-thread buffer receiving one section's palette indices.
     */
    private static final ThreadLocal<int[]> SECTION_INDICES =
            ThreadLocal.withInitial(() -> new int[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE]);

    /**
     * Per-thread buffer collecting one section's packed block instructions.
     */
    private static final ThreadLocal<long[]> SECTION_PACKED =
            ThreadLocal.withInitial(() -> new long[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE]);

    /**
     * Largest section palette translated through a per-section lookup table when capturing.
     */
    private static final int MAX_INDEXED_PALETTE = 256;

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
    public static ChunkDelta<BlockState, CompoundTag> capture(final LevelChunk chunk,
            final ChunkDelta<BlockState, CompoundTag> target,
            final String operationId) {
        if (CisNbtUtil.hasFullBlockBaseline(target.getChunkMetadata()) && !target.isDirty()) {
            return target;
        }

        // A released payload has no instructions to keep, so every section is captured again.
        final SectionCaptureCache.Record previousCapture = CisNbtUtil.hasFullBlockBaseline(target.getChunkMetadata())
                && !target.isBlockPayloadReleased()
                ? SectionCaptureCache.validRecord(target)
                : null;
        final int previousNonAirBlocks = previousCapture != null
                ? previousCapture.nonAirBlocks()
                : target.isBlockPayloadReleased()
                ? SectionCaptureCache.lastNonAirBlocks(target)
                : countPersistedNonAirBlocks(target);
        final int liveNonAirBlocks = ChunkSectionDebugUtil.countNonAirBlocks(chunk);
        backUpOnBaselineShrink(chunk, previousNonAirBlocks, liveNonAirBlocks, operationId);
        PayloadWatchTracer.traceCapturedBlocks(chunk);
        final LevelChunkSection[] sections = chunk.getSections();
        final long[] fingerprints = SectionCaptureCache.fingerprints(sections);
        final boolean[] changedSections = SectionCaptureCache.changedSections(previousCapture, fingerprints,
                sections.length);
        final Long2ObjectMap<CompoundTag> previousBlockEntities = new Long2ObjectOpenHashMap<>(target.getBlockEntities());
        if (previousCapture == null) {
            target.clearBlockPayloads(false);
        } else {
            // Unchanged sections keep the instructions the previous snapshot (plus live edits) built.
            final int chunkBottomY = chunk.getMinY();
            target.retainBlockInstructions(pos -> !isInChangedSection(changedSections, chunkBottomY, pos));
            target.clearBlockEntityPayloads(false);
        }
        captureAuthoritativeBlockBaseline(chunk, target, changedSections);
        // The delta lives as long as the chunk is loaded; the edits since the last capture grew it.
        target.trimBlockCapacity();
        SectionCaptureCache.record(target, fingerprints, liveNonAirBlocks);
        captureNonBlockState(chunk, target, previousBlockEntities);
        return target;
    }

    /**
     * Captures everything {@link #capture} does except the block instructions, for a delta whose block
     * payload is released ({@link ChunkDelta#releaseBlockPayload}): the blocks are copied out of the chunk
     * instead, as vanilla copies its sections to save them, and turned into the saved payload off the server
     * thread. The delta keeps no block instructions.
     *
     * <p>When vanilla saw nothing but entities change since the last save, the delta's block entities, light,
     * biomes, heightmaps and ticks are still the chunk's, and capturing them again on the server thread would only
     * repeat that work: they are kept. Vanilla itself saves a chunk only once something it tracks changed, so
     * what is kept is never staler than what vanilla would have on disk. The blocks are still copied, since block
     * changes that bypass Chunkis's tracking are only caught there.</p>
     *
     * @param chunk                loaded chunk
     * @param target               released delta of the chunk
     * @param nonBlockStateCurrent whether vanilla saw nothing but entities change since the last save, and the
     *                             delta holds a complete capture of the rest
     * @param operationId          save operation id
     * @return a copy of every section's block container, {@code null} where a section holds only air
     */
    public static PalettedContainer<BlockState>[] captureDeferringBlocks(final LevelChunk chunk,
            final ChunkDelta<BlockState, CompoundTag> target, final boolean nonBlockStateCurrent,
            final String operationId) {
        final int liveNonAirBlocks = ChunkSectionDebugUtil.countNonAirBlocks(chunk);
        backUpOnBaselineShrink(chunk, SectionCaptureCache.lastNonAirBlocks(target), liveNonAirBlocks, operationId);
        PayloadWatchTracer.traceCapturedBlocks(chunk);
        final LevelChunkSection[] sections = chunk.getSections();
        @SuppressWarnings("unchecked")
        final PalettedContainer<BlockState>[] blocks = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null && !sections[i].hasOnlyAir()) {
                blocks[i] = sections[i].getStates().copy();
            }
        }
        SectionCaptureCache.recordNonAirBlocks(target, liveNonAirBlocks);
        if (!nonBlockStateCurrent) {
            final Long2ObjectMap<CompoundTag> previousBlockEntities =
                    new Long2ObjectOpenHashMap<>(target.getBlockEntities());
            target.clearBlockEntityPayloads(false);
            captureNonBlockState(chunk, target, previousBlockEntities);
        }
        return blocks;
    }

    /**
     * Copies a finished chunk's heightmaps the way vanilla saves them.
     *
     * @param chunk loaded chunk
     * @return heightmaps by type id
     */
    private static CompoundTag captureHeightmaps(final LevelChunk chunk) {
        final CompoundTag heightmaps = new CompoundTag();
        for (final Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (ChunkStatus.FULL.heightmapsAfter().contains(entry.getKey())) {
                heightmaps.putLongArray(entry.getKey().getSerializationKey(), entry.getValue().getRawData().clone());
            }
        }
        return heightmaps;
    }

    /**
     * Captures block entities, chunk metadata and stored sections, the part of a snapshot besides blocks.
     *
     * @param chunk                 loaded chunk
     * @param target                delta receiving the snapshot
     * @param previousBlockEntities block-entity payloads the delta held before this capture
     */
    private static void captureNonBlockState(final LevelChunk chunk, final ChunkDelta<BlockState, CompoundTag> target,
            final Long2ObjectMap<CompoundTag> previousBlockEntities) {
        ChunkBlockEntityCapture.captureBlockEntities(chunk,
                chunk.getLevel()
                        .registryAccess(),
                target);
        retainUncapturedBlockEntities(chunk, previousBlockEntities, target);

        final CompoundTag existingMetadata = target.getChunkMetadata();
        final CompoundTag metadata = createAuthoritativeSnapshotMetadata(existingMetadata,
                BaseChunkCaptureUtil.hasPortalBlocks(chunk), StoredSections.encode(chunk), chunk.isLightCorrect(),
                PendingTicksNbt.capture(chunk));
        // Taken with the blocks, so a load of exactly these blocks need not rebuild them on the server thread.
        metadata.put(CisNbtUtil.STORED_HEIGHTMAPS_KEY, captureHeightmaps(chunk));
        target.setChunkMetadata(metadata, false);
        target.markHeightmapsCaptured();
        target.setSuppressInitialRepopulation(true);
    }

    /**
     * Keeps the previous entry as a backup when a snapshot drops most of the chunk's blocks.
     *
     * <p>A large legitimate edit (TNT quarry, //set air) looks the same as a bad capture, so the snapshot is
     * persisted either way; the previous entry is kept instead of silently refusing the user's change on every
     * save.</p>
     *
     * @param chunk                loaded chunk
     * @param previousNonAirBlocks non-air blocks of the previous snapshot
     * @param liveNonAirBlocks     non-air blocks in the chunk now
     * @param operationId          save operation id
     */
    private static void backUpOnBaselineShrink(final LevelChunk chunk, final int previousNonAirBlocks,
            final int liveNonAirBlocks, final String operationId) {
        if (!isSuspiciousBaselineShrink(previousNonAirBlocks, liveNonAirBlocks)) {
            return;
        }
        final java.nio.file.Path backup = FabricCisStorageHelper.getStorage((ServerLevel) chunk.getLevel())
                .backupEntry(FabricCisStorageHelper.toStoragePos(chunk.getPos()), "pre-shrink");
        Chunkis.LOGGER.warn("Chunkis: Full snapshot of {} in {} drops non-air blocks from {} to {}; saving it and "
                        + "keeping the previous entry at {} (suppressionCause={} chunkStatus={} saveOperationId={})",
                chunk.getPos(),
                chunk.getLevel()
                        .dimension()
                        .identifier(),
                previousNonAirBlocks,
                liveNonAirBlocks,
                backup != null ? backup : "<no backup>",
                PendingChunkMutationSuppression.currentCause(chunk),
                chunk.getPersistedStatus(),
                operationId);
    }

    /**
     * Puts back previously stored block-entity payloads the live sweep did not
     * capture, when dropping them would lose data rather than reflect an edit.
     *
     * <p>A payload is kept when the live block at its position still has a block
     * entity (the live one is missing or failed to serialize, e.g. migrated data not
     * yet materialized), or when its type id is not registered (data from a missing
     * mod, which cannot be restored now but must survive). Payloads whose block was
     * replaced by one without a block entity are dropped: that is a real edit.</p>
     *
     * @param chunk    live chunk the sweep read
     * @param previous block-entity payloads stored before the sweep
     * @param target   delta that now holds the freshly captured payloads
     */
    public static void retainUncapturedBlockEntities(final LevelChunk chunk, final Long2ObjectMap<CompoundTag> previous,
            final ChunkDelta<BlockState, CompoundTag> target) {
        final Long2ObjectMap<CompoundTag> captured = target.getBlockEntities();
        final BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();
        previous.forEach((packedPos, nbt) -> {
            if (nbt == null || captured.containsKey(packedPos)) {
                return;
            }
            final int x = BlockInstruction.unpackX(packedPos);
            final int y = BlockInstruction.unpackY(packedPos);
            final int z = BlockInstruction.unpackZ(packedPos);
            worldPos.set(chunk.getPos()
                    .getMinBlockX() + x, y, chunk.getPos()
                    .getMinBlockZ() + z);
            if (chunk.getBlockState(worldPos)
                    .hasBlockEntity() || !isRegisteredBlockEntityType(nbt)) {
                target.addBlockEntityData(x, y, z, nbt, false);
            }
        });
    }

    /**
     * Returns whether the payload's {@code id} names a registered block-entity type.
     */
    static boolean isRegisteredBlockEntityType(final CompoundTag nbt) {
        return nbt.getString("id")
                .map(Identifier::tryParse)
                .map(BuiltInRegistries.BLOCK_ENTITY_TYPE::containsKey)
                .orElse(false);
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
    private static int countPersistedNonAirBlocks(final ChunkDelta<BlockState, CompoundTag> target) {
        if (target == null || !CisNbtUtil.hasFullBlockBaseline(target.getChunkMetadata())) {
            return 0;
        }
        // Count final non-air states, not instructions: a full baseline stores explicit air too,
        // and comparing that against the live non-air count fired on every mostly-air chunk.
        final int[] nonAir = {0};
        target.forEachBlock((x, y, z, state) -> {
            if (state != null && !state.isAir()) {
                nonAir[0]++;
            }
        });
        return nonAir[0];
    }

    /**
     * Performs create authoritative snapshot metadata.
     */
    static CompoundTag createAuthoritativeSnapshotMetadata(final CompoundTag existingMetadata,
            final boolean portalChunk, final ListTag storedSections, final boolean storedLight,
            final CompoundTag pendingTicks) {
        final CompoundTag metadata = CisNbtUtil.createChunkMetadataTakingOwnership(CisNbtUtil.extractPersistedStructureMetadata(
                existingMetadata), true, true, null, portalChunk);
        if (storedSections != null) {
            metadata.put(CisNbtUtil.STORED_SECTIONS_KEY, storedSections);
        }
        metadata.putBoolean(CisNbtUtil.STORED_LIGHT_KEY, storedSections != null && storedLight);
        if (pendingTicks != null) {
            metadata.put(CisNbtUtil.PENDING_TICKS_KEY, pendingTicks);
        }
        CisNbtUtil.preserveCarriedMetadata(existingMetadata, metadata);
        return metadata;
    }

    /**
     * Records that {@code delta}'s block instructions now match {@code chunk} exactly, after a restore
     * that applied a full baseline, so the next snapshot re-reads only sections that change.
     *
     * @param chunk restored live chunk
     * @param delta runtime delta holding the restored baseline
     */
    public static void recordRestoredBaseline(final LevelChunk chunk, final ChunkDelta<BlockState, CompoundTag> delta) {
        SectionCaptureCache.record(delta, SectionCaptureCache.fingerprints(chunk.getSections()),
                ChunkSectionDebugUtil.countNonAirBlocks(chunk));
    }

    /**
     * Appends every non-air block of the changed sections to {@code target}.
     *
     * @param chunk           live chunk
     * @param target          delta receiving the instructions
     * @param changedSections per-section flags; only flagged sections are read
     */
    private static void captureAuthoritativeBlockBaseline(final LevelChunk chunk,
            final ChunkDelta<BlockState, CompoundTag> target, final boolean[] changedSections) {
        final LevelChunkSection[] sections = chunk.getSections();
        final int chunkBottomY = chunk.getMinY();
        int expectedBlocks = 0;
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            final LevelChunkSection section = sections[sectionIndex];
            if (changedSections[sectionIndex] && section != null && !section.hasOnlyAir()) {
                expectedBlocks += ((ChunkSectionAccessor) section).chunkis$getNonEmptyBlockCount();
            }
        }
        target.ensureBlockCapacity(expectedBlocks);
        final IdentityHashMap<BlockState, Integer> paletteIds = new IdentityHashMap<>();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            final LevelChunkSection section = sections[sectionIndex];
            if (!changedSections[sectionIndex] || section == null || section.hasOnlyAir()) {
                continue;
            }
            final PalettedContainer<BlockState> container = section.getStates();
            if (!captureSectionByPaletteIndex(container, target, chunkBottomY, sectionIndex, paletteIds)) {
                captureSectionByState(container, target, chunkBottomY, sectionIndex, paletteIds);
            }
        }
    }

    /**
     * Appends one section's non-air blocks to a delta, as a capture does.
     *
     * @param container    section blocks
     * @param target       delta to append to
     * @param chunkBottomY chunk bottom Y
     * @param sectionIndex section index
     * @param paletteIds   delta palette ids by state, shared across a chunk's sections
     */
    public static void appendSectionBlocks(final PalettedContainer<BlockState> container,
            final ChunkDelta<BlockState, CompoundTag> target, final int chunkBottomY, final int sectionIndex,
            final IdentityHashMap<BlockState, Integer> paletteIds) {
        if (!captureSectionByPaletteIndex(container, target, chunkBottomY, sectionIndex, paletteIds)) {
            captureSectionByState(container, target, chunkBottomY, sectionIndex, paletteIds);
        }
    }

    /**
     * Reads a section through its raw storage, translating each palette entry to a delta palette id
     * once. Only used for small section palettes; the global palette would make the table huge.
     *
     * @param container    section block-state container
     * @param target       delta receiving the instructions
     * @param chunkBottomY chunk bottom Y
     * @param sectionIndex section index
     * @param paletteIds   cache from block state to delta palette id
     * @return false when the caller must fall back to {@link #captureSectionByState}
     */
    private static boolean captureSectionByPaletteIndex(final PalettedContainer<BlockState> container,
            final ChunkDelta<BlockState, CompoundTag> target, final int chunkBottomY, final int sectionIndex,
            final IdentityHashMap<BlockState, Integer> paletteIds) {
        if (!PalettedContainerFields.AVAILABLE) {
            return false;
        }
        final Object data = PalettedContainerFields.data(container);
        final Palette<BlockState> palette = PalettedContainerFields.palette(data);
        final int paletteSize = palette.getSize();
        if (paletteSize > MAX_INDEXED_PALETTE) {
            return false;
        }
        final int[] deltaIds = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            final BlockState state = palette.valueFor(i);
            if (state == null) {
                return false;
            }
            deltaIds[i] = state.isAir() ? -1 : deltaPaletteId(target, state, paletteIds);
        }
        final BitStorage storage = PalettedContainerFields.storage(data);
        // One bulk unpack instead of a packed read per position.
        final int[] indices = SECTION_INDICES.get();
        storage.unpack(indices);
        // Packed into a buffer and appended in one copy instead of one append call per block.
        final long[] packed = SECTION_PACKED.get();
        int count = 0;
        for (int index = 0; index < SECTION_SIZE * SECTION_SIZE * SECTION_SIZE; index++) {
            final int deltaId = deltaIds[indices[index]];
            if (deltaId >= 0) {
                packed[count++] = ChunkDelta.packDecodedBlock(index & (SECTION_SIZE - 1),
                        toWorldY(chunkBottomY, sectionIndex, index >>> 8), (index >>> 4) & (SECTION_SIZE - 1),
                        deltaId);
            }
        }
        target.appendDecodedBlocks(packed, count);
        return true;
    }

    /**
     * Reads a section one block state at a time.
     *
     * @param container    section block-state container
     * @param target       delta receiving the instructions
     * @param chunkBottomY chunk bottom Y
     * @param sectionIndex section index
     * @param paletteIds   cache from block state to delta palette id
     */
    @SuppressWarnings("unchecked")
    private static void captureSectionByState(final PalettedContainer<BlockState> container,
            final ChunkDelta<BlockState, CompoundTag> target, final int chunkBottomY, final int sectionIndex,
            final IdentityHashMap<BlockState, Integer> paletteIds) {
        final PalettedContainerAccessor<BlockState> rawContainer = (PalettedContainerAccessor<BlockState>) container;
        for (int index = 0; index < SECTION_SIZE * SECTION_SIZE * SECTION_SIZE; index++) {
            final BlockState state = rawContainer.chunkis$get(index);
            if (!state.isAir()) {
                appendSectionBlock(target, chunkBottomY, sectionIndex, index, deltaPaletteId(target, state, paletteIds));
            }
        }
    }

    /**
     * Resolves a block state's delta palette id through a per-capture cache.
     *
     * @param target     delta owning the palette
     * @param state      block state
     * @param paletteIds cache from block state to delta palette id
     * @return delta palette id
     */
    private static int deltaPaletteId(final ChunkDelta<BlockState, CompoundTag> target, final BlockState state,
            final IdentityHashMap<BlockState, Integer> paletteIds) {
        final Integer cached = paletteIds.get(state);
        if (cached != null) {
            return cached;
        }
        final int paletteId = target.getBlockPalette()
                .getOrAdd(state);
        paletteIds.put(state, paletteId);
        return paletteId;
    }

    /**
     * Appends one block given its section-local storage index (Y-Z-X order).
     *
     * @param target       delta receiving the instruction
     * @param chunkBottomY chunk bottom Y
     * @param sectionIndex section index
     * @param index        section-local storage index
     * @param paletteId    delta palette id
     */
    private static void appendSectionBlock(final ChunkDelta<BlockState, CompoundTag> target, final int chunkBottomY,
            final int sectionIndex, final int index, final int paletteId) {
        target.appendDecodedBlockFast(index & (SECTION_SIZE - 1),
                toWorldY(chunkBottomY, sectionIndex, index >>> 8),
                (index >>> 4) & (SECTION_SIZE - 1),
                paletteId);
    }

    /**
     * Reports whether a packed position lies in a section flagged for re-reading, or outside the
     * chunk's current sections.
     *
     * @param changedSections per-section flags
     * @param chunkBottomY    chunk bottom Y
     * @param pos             packed position
     * @return true when the instruction must be dropped before re-reading
     */
    private static boolean isInChangedSection(final boolean[] changedSections, final int chunkBottomY,
            final long pos) {
        final int sectionIndex = (BlockInstruction.unpackY(pos) - chunkBottomY) >> 4;
        return sectionIndex < 0 || sectionIndex >= changedSections.length || changedSections[sectionIndex];
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
