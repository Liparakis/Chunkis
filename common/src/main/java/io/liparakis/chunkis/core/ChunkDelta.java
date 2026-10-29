package io.liparakis.chunkis.core;

import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Compact storage for modified chunk data.
 *
 * <p>This class stores sparse block changes, block entity payloads, entity
 * payloads, and chunk-level metadata for one Minecraft chunk. It is designed for
 * low allocation pressure and fast save/load paths.</p>
 *
 * <p>Storage layout:</p>
 * <ul>
 *   <li>block changes are stored as packed {@code long} instructions</li>
 *   <li>block states are deduplicated through a palette</li>
 *   <li>position lookup uses primitive fastutil maps</li>
 *   <li>block entities and entities are allocated lazily</li>
 *   <li>metadata may cache its pre-encoded payload for faster re-save</li>
 * </ul>
 *
 * <p>This class is not internally synchronized. Callers must avoid concurrent
 * mutation of the same delta instance.</p>
 *
 * @param <S> block state type
 * @param <N> NBT/data payload type
 * @see BlockInstruction
 * @see Palette
 */
public final class ChunkDelta<S, N> implements ChunkDeltaView<S, N> {

    /**
     * Stores source.
     */
    private static final String SOURCE = "ChunkDelta";

    /**
     * Mask for the low 32 bits containing the packed local block position.
     *
     * <p>The high 32 bits store the palette id.</p>
     */
    private static final long POSITION_MASK = 0xFFFF_FFFFL;

    /**
     * Shared default predicate for deltas created without an air/empty-state test.
     */
    private static final Predicate<Object> NEVER_EMPTY_STATE = ignored -> false;

    /**
     * Storage for packed block instructions and position map.
     */
    private final BlockInstructionStorage instructions;

    /**
     * Palette mapping block states to compact integer ids.
     */
    private final Palette<S> blockPalette;
    /**
     * Ownership and dirty state metadata tracking.
     */
    private final DeltaOwnershipState ownershipState;
    /**
     * Predicate used to identify states that should clear block entity payloads.
     */
    private final Predicate<S> isEmptyState;
    /**
     * Lazily allocated block entity payloads keyed by packed position.
     */
    private Long2ObjectOpenHashMap<N> blockEntities;
    /**
     * Lazily allocated active entities keyed by runtime entity id.
     */
    private Int2ObjectOpenHashMap<N> activeEntities;
    /**
     * Entity payloads loaded from disk/network that have not been spawned yet.
     *
     * <p>Starts as {@link Collections#emptyList()} to avoid allocating an
     * {@link ArrayList} for block-only deltas.</p>
     */
    private List<N> pendingEntities;
    /**
     * Chunk-level metadata used to preserve deterministic reload state.
     *
     * <p>Examples include structure metadata, replay suppression flags, or a
     * serialized vanilla-compatible base chunk payload.</p>
     */
    private N chunkMetadata;
    /**
     * Cached encoded metadata payload, including the length prefix written by the
     * NBT adapter.
     */
    private byte[] encodedChunkMetadata;
    /**
     * Hash of {@link #encodedChunkMetadata}, used as a cheap first-pass equality
     * check before byte comparison.
     */
    private int encodedChunkMetadataHash;
    /**
     * CIS format version this delta was decoded from or last saved as.
     */
    private int sourceVersion;
    /**
     * Whether restored loads should suppress one-time vanilla repopulation work.
     */
    private boolean suppressInitialRepopulation;

    /**
     * Whether the block instructions were released ({@link #releaseBlockPayload}); in-memory state only.
     */
    private boolean blockPayloadReleased;

    /**
     * Live block edits applied to the instructions; with the bulk epoch it stamps the block payload state.
     */
    private long liveBlockEdits;

    /**
     * For a delta rebuilt from its stored entry with a released delta's edits on top, those edits; consumed by
     * the restore, never stored.
     */
    private ChunkDeltaView<S, N> storedEntryEdits;

    /**
     * Creates an empty delta with an explicit empty-state predicate.
     *
     * @param isEmptyState predicate used to identify empty states such as air
     */
    public ChunkDelta(final Predicate<S> isEmptyState) {
        this.instructions = new BlockInstructionStorage();
        this.blockPalette = new Palette<>();
        this.blockEntities = null;
        this.activeEntities = null;
        this.pendingEntities = Collections.emptyList();
        this.chunkMetadata = null;
        this.encodedChunkMetadata = null;
        this.encodedChunkMetadataHash = 0;
        this.ownershipState = new DeltaOwnershipState();
        this.sourceVersion = CisConstants.VERSION;
        this.suppressInitialRepopulation = false;
        this.isEmptyState = Objects.requireNonNull(isEmptyState, "isEmptyState");
    }

    /**
     * Creates an empty delta with no empty-state predicate.
     *
     * <p>Used by decode paths where the block-state adapter is not available yet.
     * In this mode, no state is treated as empty.</p>
     */
    @SuppressWarnings("unchecked")
    public ChunkDelta() {
        this((Predicate<S>) NEVER_EMPTY_STATE);
    }

    /**
     * Packs a palette id and position key into one block instruction.
     *
     * @param paletteId palette id
     * @param posKey    packed local position
     * @return packed instruction
     */
    private static long packInstruction(final int paletteId, final long posKey) {
        return ((long) paletteId << Integer.SIZE) | (posKey & POSITION_MASK);
    }

    /**
     * Copies an entity list into the internal representation.
     *
     * @param entities source entity list, may be {@code null}
     * @return empty singleton or mutable copy
     */
    private static <N> List<N> normalizeEntityList(final List<N> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }

        return new ArrayList<>(entities);
    }

    /**
     * Creates a detached read-only snapshot tailored for async encode/save work.
     *
     * @param payloadCopier copies each stored payload value
     * @return read-only snapshot view of this delta
     */
    public ChunkDeltaSnapshotView<S, N> snapshotView(final UnaryOperator<N> payloadCopier) {
        return snapshotView(payloadCopier, payloadCopier);
    }

    /**
     * Same as {@link #snapshotView(UnaryOperator)}, copying block-entity and entity payloads and the chunk
     * metadata with separate strategies: a caller that knows payloads are never changed in place, only replaced,
     * can share them and copy just the metadata.
     *
     * @param payloadCopier  copies each block-entity and entity payload
     * @param metadataCopier copies the chunk metadata
     * @return read-only snapshot view of this delta
     */
    public ChunkDeltaSnapshotView<S, N> snapshotView(final UnaryOperator<N> payloadCopier,
            final UnaryOperator<N> metadataCopier) {
        return new ChunkDeltaSnapshotView<>(
                this.instructions.packedInstructions,
                this.instructions.instructionCount,
                this.blockPalette,
                this.blockEntities,
                this.activeEntities,
                this.pendingEntities,
                this.chunkMetadata,
                this.encodedChunkMetadata,
                this.sourceVersion,
                this.ownershipState.mutationGeneration,
                this.suppressInitialRepopulation,
                isDirty(),
                payloadCopier,
                metadataCopier
        );
    }

    /**
     * Same as {@link #snapshotView}, without block instructions, for a save whose blocks come from elsewhere;
     * skips copying an instruction array the save would not read.
     *
     * @param payloadCopier copies block-entity, entity and metadata payloads
     * @return snapshot holding no block instructions
     */
    public ChunkDeltaSnapshotView<S, N> snapshotViewWithoutBlocks(final UnaryOperator<N> payloadCopier) {
        return snapshotViewWithoutBlocks(payloadCopier, payloadCopier);
    }

    /**
     * Same as {@link #snapshotViewWithoutBlocks(UnaryOperator)}, with separate copy strategies for payloads and
     * metadata ({@link #snapshotView(UnaryOperator, UnaryOperator)}).
     *
     * @param payloadCopier  copies each block-entity and entity payload
     * @param metadataCopier copies the chunk metadata
     * @return snapshot holding no block instructions
     */
    public ChunkDeltaSnapshotView<S, N> snapshotViewWithoutBlocks(final UnaryOperator<N> payloadCopier,
            final UnaryOperator<N> metadataCopier) {
        return new ChunkDeltaSnapshotView<>(
                new long[0],
                0,
                this.blockPalette,
                this.blockEntities,
                this.activeEntities,
                this.pendingEntities,
                this.chunkMetadata,
                this.encodedChunkMetadata,
                this.sourceVersion,
                this.ownershipState.mutationGeneration,
                this.suppressInitialRepopulation,
                isDirty(),
                payloadCopier,
                metadataCopier
        );
    }

    /**
     * Seeds this delta's block palette from another delta before bulk decoded appends.
     *
     * <p>This is used by restore-time runtime-delta reconstruction so copied block
     * instructions can reuse already-decoded palette ids instead of hashing block
     * states back through {@link Palette#getOrAdd(Object)}.</p>
     *
     * @param source source delta whose palette should be copied
     */
    public void copyBlockPaletteFrom(final ChunkDelta<S, ?> source) {
        Objects.requireNonNull(source, "source");
        blockPalette.replaceWith(source.blockPalette);
        instructions.bulkEpoch++;
    }

    /**
     * Traces a dirty/clean transition when dirty-tracking diagnostics are enabled.
     *
     * @param becameDirty {@code true} for clean-to-dirty, {@code false} for dirty-to-clean
     */
    private static void traceDirtyTransition(final boolean becameDirty) {
        if (!ChunkisDebugConfig.allows(ChunkisDebugDomain.DIRTY_TRACKING, ChunkTraceSeverity.INFO)) {
            return;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.DIRTY_TRACKING,
                becameDirty ? ChunkTraceEventType.DELTA_MARKED_DIRTY : ChunkTraceEventType.DELTA_MARKED_CLEAN,
                ChunkTraceSeverity.INFO,
                becameDirty ? ChunkTraceReason.DELTA_BECAME_DIRTY : ChunkTraceReason.DELTA_MARKED_SAVED,
                SOURCE,
                becameDirty ? "delta became dirty" : "delta marked clean",
                null,
                null,
                null,
                null,
                becameDirty,
                null
        );
    }

    /**
     * Records one semantic mutation.
     */
    private void markDirtyInternal() {
        final boolean wasDirty = isDirty();
        ownershipState.incrementMutationGeneration();
        if (!wasDirty) {
            if (ownershipState.firstMutationSource == null) {
                ownershipState.firstMutationSource =
                        ownershipState.pendingMutationSource != null ? ownershipState.pendingMutationSource : SOURCE;
            }
            traceDirtyTransition(true);
        }
        ownershipState.pendingMutationSource = null;
    }

    /**
     * Adds or updates a block change and marks the delta dirty.
     *
     * @param x        local chunk X coordinate
     * @param y        block Y coordinate
     * @param z        local chunk Z coordinate
     * @param newState new block state
     */
    public void addBlockChange(
            final int x,
            final int y,
            final int z,
            final S newState
    ) {
        addBlockChange(x, y, z, newState, true);
    }

    /**
     * Adds or updates a block change.
     *
     * @param x         local chunk X coordinate
     * @param y         block Y coordinate
     * @param z         local chunk Z coordinate
     * @param newState  new block state
     * @param markDirty whether to mark the delta dirty when data changes
     */
    public void addBlockChange(
            final int x,
            final int y,
            final int z,
            final S newState,
            final boolean markDirty
    ) {
        if (newState == null) {
            return;
        }

        final int paletteId = blockPalette.getOrAdd(newState);
        final long posKey = BlockInstruction.packPos(x, y, z);
        instructions.ensurePositionMap();
        final int existingIndex = instructions.positionMap.get(posKey);

        if (existingIndex != -1) {
            updateExistingInstruction(paletteId, posKey, existingIndex, markDirty);
        } else {
            addNewInstruction(paletteId, posKey, markDirty);
        }

        cleanupBlockEntityIfEmpty(newState, posKey, markDirty);
    }

    /**
     * Updates an existing block instruction.
     *
     * @param paletteId palette id for the new state
     * @param posKey    packed local position
     * @param index     existing instruction index
     * @param markDirty whether to mark dirty if the instruction changes
     */
    private void updateExistingInstruction(
            final int paletteId,
            final long posKey,
            final int index,
            final boolean markDirty
    ) {
        final long newInstruction = packInstruction(paletteId, posKey);

        if (instructions.packedInstructions[index] == newInstruction) {
            return;
        }

        instructions.packedInstructions[index] = newInstruction;
        liveBlockEdits++;

        if (markDirty) {
            markDirtyInternal();
        }
    }

    /**
     * Appends a new block instruction.
     *
     * @param paletteId palette id for the state
     * @param posKey    packed local position
     * @param markDirty whether to mark dirty
     */
    private void addNewInstruction(
            final int paletteId,
            final long posKey,
            final boolean markDirty
    ) {
        instructions.add(packInstruction(paletteId, posKey), posKey);
        liveBlockEdits++;

        if (markDirty) {
            markDirtyInternal();
        }
    }

    /**
     * Removes block entity payload if the new state is considered empty.
     *
     * @param state  new block state
     * @param posKey packed local position
     * @param markDirty whether a removed payload marks the delta dirty, so the removal
     *                  is persisted even when the block instruction itself was unchanged
     */
    private void cleanupBlockEntityIfEmpty(final S state, final long posKey, final boolean markDirty) {
        if (blockEntities != null && isEmptyState.test(state) && blockEntities.remove(posKey) != null && markDirty) {
            markDirtyInternal();
        }
    }

    /**
     * Removes block entity data and marks the delta dirty if data existed.
     *
     * @param x local chunk X coordinate
     * @param y block Y coordinate
     * @param z local chunk Z coordinate
     */
    public void removeBlockEntityData(final int x, final int y, final int z) {
        removeBlockEntityData(x, y, z, true);
    }

    /**
     * Removes block entity data.
     *
     * @param x         local chunk X coordinate
     * @param y         block Y coordinate
     * @param z         local chunk Z coordinate
     * @param markDirty whether to mark dirty if data was removed
     */
    public void removeBlockEntityData(
            final int x,
            final int y,
            final int z,
            final boolean markDirty
    ) {
        if (blockEntities == null) {
            return;
        }

        final long posKey = BlockInstruction.packPos(x, y, z);

        if (blockEntities.remove(posKey) != null) {
            if (markDirty) {
                markDirtyInternal();
            }
        }
    }

    /**
     * Clears block and block-entity payloads.
     *
     * <p>Used when a persisted serialized base chunk has taken ownership of the
     * full block grid, leaving CIS block payloads to represent only future sparse
     * edits.</p>
     *
     * @param markDirty whether to mark dirty if payloads were cleared
     */
    public void clearBlockPayloads(final boolean markDirty) {
        blockPayloadReleased = false;
        if (instructions.instructionCount == 0 && (blockEntities == null || blockEntities.isEmpty())) {
            return;
        }

        instructions.clear();

        if (blockEntities != null) {
            blockEntities.clear();
        }

        if (markDirty) {
            markDirtyInternal();
        }
    }

    /**
     * Returns a counter that changes whenever block instructions are rewritten in bulk (cleared,
     * decoded, appended without a position lookup, palette-rewritten or retained). Live block
     * edits through {@link #addBlockChange} do not change it.
     *
     * @return current bulk-change counter
     */
    public long getBlockPayloadEpoch() {
        return instructions.bulkEpoch;
    }

    /**
     * Keeps only the block instructions whose packed position passes {@code keepPosition}, in their
     * original order. Does not touch block entities, the palette or the dirty flag.
     *
     * @param keepPosition test on a packed position as produced by {@link BlockInstruction#packPos}
     */
    public void retainBlockInstructions(final java.util.function.LongPredicate keepPosition) {
        instructions.retain(keepPosition);
    }

    /**
     * Pre-sizes block instruction storage for bulk decode paths.
     *
     * @param additionalBlocks number of additional block instructions expected
     */
    public void ensureBlockCapacity(final int additionalBlocks) {
        instructions.ensureBlockCapacity(additionalBlocks);
    }

    /**
     * Drops the block instructions of a clean delta whose blocks are on disk and held by a loaded chunk, so a
     * loaded chunk does not keep a second copy of its blocks. Block entities, entities and metadata stay.
     *
     * <p>Until a capture rebuilds them from the live chunk ({@link #clearBlockPayloads} ends the released
     * state), the instructions are only an overlay on the stored entry ({@link #isBlockPayloadReleased()}):
     * the delta must not be encoded or used to rebuild a chunk as it is. Later edits are still recorded, on top
     * of the stored blocks.</p>
     *
     * @throws IllegalStateException if the delta has unsaved changes, which only its instructions may hold
     */
    public void releaseBlockPayload() {
        if (isDirty()) {
            throw new IllegalStateException("Refusing to release the block payload of a dirty delta");
        }
        instructions.release();
        blockPayloadReleased = true;
    }

    /**
     * Reports whether {@link #releaseBlockPayload} dropped the block instructions and no capture has rebuilt
     * them since.
     *
     * <p>A released delta's instructions are an overlay on its stored entry: the entry's blocks with the
     * instructions applied on top are the chunk's blocks. The release needs a clean delta, so the entry then
     * held the blocks; every later edit is recorded; and every later write of the entry writes the chunk's
     * whole blocks, which already contain the edits. Callers that need the blocks of a released delta whose
     * chunk is gone read the entry and apply the instructions.</p>
     *
     * @return true while the delta's blocks are its stored entry's plus its instructions
     */
    public boolean isBlockPayloadReleased() {
        return blockPayloadReleased;
    }

    /**
     * Releases the block payload of a delta whose instructions are its stored entry's blocks with {@code edits}
     * on top, keeping only those edits, which then form the overlay ({@link #isBlockPayloadReleased()}). Unlike
     * {@link #releaseBlockPayload()} it accepts a dirty delta: its only block changes the entry lacks are the
     * edits it keeps. Other unsaved changes stay, with the dirty flag.
     *
     * @param edits view whose block instructions are the edits over the stored entry
     */
    public void releaseBlockPayloadKeeping(final ChunkDeltaView<S, N> edits) {
        Objects.requireNonNull(edits, "edits");
        instructions.release();
        blockPayloadReleased = true;
        edits.forEachBlock((x, y, z, state) -> addBlockChange(x, y, z, state, false));
    }

    /**
     * Marks this delta as rebuilt from its stored entry with {@code edits}, a released delta's recorded edits,
     * applied on top, so the restore can release it again keeping only those edits.
     *
     * @param edits view whose block instructions are the edits over the stored entry
     */
    public void markRebuiltFromStoredEntry(final ChunkDeltaView<S, N> edits) {
        storedEntryEdits = Objects.requireNonNull(edits, "edits");
    }

    /**
     * Returns and forgets the edits set by {@link #markRebuiltFromStoredEntry}.
     *
     * @return the edits over the stored entry, or {@code null} when the delta was not rebuilt from it
     */
    public ChunkDeltaView<S, N> takeStoredEntryEdits() {
        final ChunkDeltaView<S, N> edits = storedEntryEdits;
        storedEntryEdits = null;
        return edits;
    }

    /**
     * Fingerprint of the blocks this chunk's stored entry was last written with from copied sections: their content
     * hash then the written block part's hash; {@code null} when unknown. Lets a later save whose sections hash the
     * same copy the stored block part instead of encoding the blocks again.
     */
    private long[] writtenBlocksPrint;

    /**
     * The blocks this delta was filled with, in a form a chunk load can use as is (opaque here), valid only while
     * no block changes; see {@link #attachLoadedBlocks}.
     */
    private Object loadedBlocks;

    /**
     * Block state stamp when {@link #loadedBlocks} was attached.
     */
    private long loadedBlocksStamp;

    /**
     * Attaches the form the blocks this delta was just filled with came in, for the chunk load to use instead of
     * rebuilding it from the instructions.
     *
     * @param blocks the blocks in that form
     */
    public void attachLoadedBlocks(final Object blocks) {
        this.loadedBlocks = blocks;
        this.loadedBlocksStamp = blockStateStamp();
    }

    /**
     * Takes the attached form of the blocks, once, and only while the delta still holds exactly those blocks: any
     * block change since voids it.
     *
     * @return the attached blocks, or {@code null}
     */
    public Object takeLoadedBlocks() {
        final Object blocks = loadedBlocks;
        loadedBlocks = null;
        return blocks != null && loadedBlocksStamp == blockStateStamp() ? blocks : null;
    }

    /**
     * Combines every counter a block change moves.
     *
     * @return the current block state stamp
     */
    private long blockStateStamp() {
        return (getBlockEditStamp() * 31 + instructions.instructionCount) * 31 + ownershipState.mutationGeneration;
    }

    /**
     * Hash of the block part of the stored entry this delta was decoded from ({@code CisBlockPart}), or
     * {@code null} when it was not decoded from a current-version entry.
     */
    private long[] storedBlockPartHash;

    /**
     * Returns the hash of the block part of the entry this delta was decoded from.
     *
     * @return two longs, or {@code null}
     */
    public long[] getStoredBlockPartHash() {
        return storedBlockPartHash;
    }

    /**
     * Records the hash of the block part of the entry this delta was decoded from; storage calls this.
     *
     * @param hash two longs, or {@code null}
     */
    public void setStoredBlockPartHash(final long[] hash) {
        this.storedBlockPartHash = hash;
    }

    /**
     * Returns the fingerprint of the blocks last written from copied sections.
     *
     * @return section hash then block part hash (four longs), or {@code null}
     */
    public long[] getWrittenBlocksPrint() {
        return writtenBlocksPrint;
    }

    /**
     * Records, or clears, the fingerprint of the blocks the stored entry was just written with.
     *
     * @param print section hash then block part hash (four longs), or {@code null} when unknown
     */
    public void setWrittenBlocksPrint(final long[] print) {
        this.writtenBlocksPrint = print;
    }

    /**
     * Returns a stamp of the block payload: it changes whenever block instructions change, through a live edit
     * or a bulk rewrite, and never otherwise.
     *
     * @return current block payload stamp
     */
    public long getBlockEditStamp() {
        return instructions.bulkEpoch + liveBlockEdits;
    }

    /**
     * Releases the block payload once a save wrote every block of the chunk as it was at {@code stamp}, and no
     * block changed since: the stored entry then holds the delta's blocks. A full payload is released; a released
     * one drops the edits it recorded since its release, leaving an empty overlay
     * ({@link #isBlockPayloadReleased()}). Unlike {@link #releaseBlockPayload()} this accepts a dirty delta: the
     * entry holds every block, so only non-block changes stay unsaved, with the dirty flag.
     *
     * @param stamp {@link #getBlockEditStamp()} when the save took the blocks
     */
    public void releaseBlocksSavedAt(final long stamp) {
        if (getBlockEditStamp() != stamp || (blockPayloadReleased && instructions.instructionCount == 0)) {
            return;
        }
        instructions.release();
        blockPayloadReleased = true;
    }

    /**
     * Copies a payload, keeping {@code null} as it is.
     *
     * @param payload       payload, may be {@code null}
     * @param payloadCopier copier for non-null payloads
     * @param <N>           payload type
     * @return the copy, or {@code null}
     */
    private static <N> N copyPayload(final N payload, final UnaryOperator<N> payloadCopier) {
        return payload == null ? null : payloadCopier.apply(payload);
    }

    /**
     * Replaces everything but the block payload with copies of {@code source}'s: block entities, active and
     * pending entities, chunk metadata and the repopulation flag. Blocks, palette and source version stay.
     * Marks this delta dirty when {@code source} is dirty, so its unsaved changes are saved again.
     *
     * <p>Used to rebuild a released delta ({@link #isBlockPayloadReleased()}) from its stored entry: the entry
     * and the delta's overlay supply the blocks, the newer delta everything else.</p>
     *
     * @param source        delta whose non-block state is taken
     * @param payloadCopier copies each block-entity, entity and metadata payload
     */
    public void adoptNonBlockStateFrom(final ChunkDelta<S, N> source, final UnaryOperator<N> payloadCopier) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(payloadCopier, "payloadCopier");
        blockEntities = null;
        if (source.blockEntities != null && !source.blockEntities.isEmpty()) {
            blockEntities = new Long2ObjectOpenHashMap<>(source.blockEntities.size());
            for (final Long2ObjectMap.Entry<N> entry : source.blockEntities.long2ObjectEntrySet()) {
                blockEntities.put(entry.getLongKey(), copyPayload(entry.getValue(), payloadCopier));
            }
        }
        activeEntities = null;
        if (source.activeEntities != null && !source.activeEntities.isEmpty()) {
            activeEntities = new Int2ObjectOpenHashMap<>(source.activeEntities.size());
            source.activeEntities.int2ObjectEntrySet()
                    .forEach(entry -> activeEntities.put(entry.getIntKey(), copyPayload(entry.getValue(), payloadCopier)));
        }
        pendingEntities = Collections.emptyList();
        if (!source.pendingEntities.isEmpty()) {
            pendingEntities = new ArrayList<>(source.pendingEntities.size());
            source.pendingEntities.forEach(nbt -> pendingEntities.add(copyPayload(nbt, payloadCopier)));
        }
        chunkMetadata = copyPayload(source.chunkMetadata, payloadCopier);
        encodedChunkMetadata = null;
        encodedChunkMetadataHash = 0;
        suppressInitialRepopulation = source.suppressInitialRepopulation;
        if (source.isDirty()) {
            markDirtyInternal();
        }
    }

    /**
     * Releases unused block-instruction capacity; the stored blocks and their order are unchanged.
     */
    public void trimBlockCapacity() {
        instructions.trimToSize();
    }

    /**
     * Appends every block instruction of {@code source}, in order, as {@link #appendDecodedBlockFast} would one
     * by one. The palette ids are copied as they are, so call {@link #copyBlockPaletteFrom} first.
     *
     * @param source delta to copy block instructions from; not modified
     */
    public void appendDecodedBlocksFrom(final ChunkDelta<S, ?> source) {
        Objects.requireNonNull(source, "source");
        instructions.addAllAppendOnly(source.instructions);
    }

    /**
     * Packs one decoded block the way {@link #appendDecodedBlockFast} stores it, for
     * {@link #appendDecodedBlocks}.
     *
     * @param x         local chunk X coordinate
     * @param y         block Y coordinate
     * @param z         local chunk Z coordinate
     * @param paletteId palette id in this delta's block palette
     * @return packed instruction
     */
    public static long packDecodedBlock(final int x, final int y, final int z, final int paletteId) {
        return packInstruction(paletteId, BlockInstruction.packPos(x, y, z));
    }

    /**
     * Appends blocks packed by {@link #packDecodedBlock}, in order, as {@link #appendDecodedBlockFast} would one
     * by one; positions must not already be present.
     *
     * @param packed packed blocks
     * @param count  number of leading entries of {@code packed} to append
     */
    public void appendDecodedBlocks(final long[] packed, final int count) {
        instructions.addAllAppendOnly(packed, count);
    }

    /**
     * Fast append path for decode operations where positions are guaranteed
     * unique and no immediate position-map lookup is needed.
     *
     * @param x         local chunk X coordinate
     * @param y         block Y coordinate
     * @param z         local chunk Z coordinate
     * @param paletteId decoded palette id
     */
    public void appendDecodedBlockFast(
            final int x,
            final int y,
            final int z,
            final int paletteId
    ) {
        final long posKey = BlockInstruction.packPos(x, y, z);
        instructions.addAppendOnly(packInstruction(paletteId, posKey));
    }

    /**
     * Fills all 4096 positions of a section with a single palette id.
     *
     * <p>This is the bulk fast-path used by {@code fillSection} in the decoder.
     * Rather than issuing 4096 individual {@link #appendDecodedBlockFast} calls,
     * this method precomputes the constant upper 32 bits (palette id), iterates
     * the y/z/x positions in the canonical order, and writes directly into
     * the packed instruction array.</p>
     *
     * <p>Packed position layout (matches {@link BlockInstruction#packPos}):</p>
     * <pre>
     *   bits 31-12  Y coordinate (20-bit, sign-extended)
     *   bits 11-8   X coordinate (4-bit)
     *   bits  7-4   Z coordinate (4-bit)
     *   bits  3-0   Reserved (0)
     * </pre>
     *
     * <p><strong>Order invariant:</strong> blocks are written in y->z->x order,
     * matching the index formula used by the default-sparse exception path:
     * {@code startOffset + (y << 8) + (z << 4) + x}.</p>
     *
     * @param sectionY  section Y coordinate (block Y >> 4)
     * @param paletteId palette id to fill with
     */
    public void fillDecodedSection(final int sectionY, final int paletteId) {
        final int baseY = sectionY << 4;
        // Pre-size storage for 4096 additional instructions.
        instructions.ensureBlockCapacity(4096);

        // Precompute the constant upper half of the packed instruction.
        final long paletteHigh = (long) paletteId << Integer.SIZE;

        // Write directly into the backing array, bypassing per-block method calls.
        // The inner loop is intentionally tight: no capacity check per iteration
        // (guaranteed by ensureBlockCapacity above), no dirty flag update per
        // iteration (set once at end).
        final long[] arr = instructions.packedInstructions;
        int count = instructions.instructionCount;

        for (int y = 0; y < 16; y++) {
            // Precompute the y-contribution to the position key once per y-slice.
            final long yBits = (long) ((baseY + y) & 0xFFFFF) << 12;
            for (int z = 0; z < 16; z++) {
                final long yzBits = yBits | ((long) z << 4);
                for (int x = 0; x < 16; x++) {
                    arr[count++] = paletteHigh | yzBits | ((long) x << 8);
                }
            }
        }

        instructions.instructionCount = count;
        instructions.positionMapDirty = true;
        instructions.bulkEpoch++;
    }

    /**
     * Appends all non-zero entries from a dense section's already-translated
     * global palette index array directly into the instruction store.
     *
     * <p>This is the inner-loop fast-path used by {@code readDenseBlocks}.
     * Instead of calling {@link #appendDecodedBlockFast} per block (which
     * traverses a method-call chain per entry), this method writes directly
     * into the backing array in a single tight loop.</p>
     *
     * <p>The {@code globalIndices} array must be indexed in y->z->x order
     * (same as the dense section storage format), length exactly 4096.</p>
     *
     * @param baseY         absolute block Y of this section's bottom (sectionY << 4)
     * @param globalIndices 4096-element array of global palette ids; a negative entry
     *                      means "no change" and is skipped, any other value (including
     *                      {@code 0}, air) is written as an instruction
     */
    public void appendDecodedDenseSection(final int baseY, final int[] globalIndices) {
        // Upper bound: at most 4096 new instructions. Pre-size to avoid any
        // mid-loop capacity checks in addAppendOnly.
        instructions.ensureBlockCapacity(4096);

        final long[] arr = instructions.packedInstructions;
        int count = instructions.instructionCount;
        int written = 0;
        int flatIdx = 0;

        for (int y = 0; y < 16; y++) {
            final long yBits = (long) ((baseY + y) & 0xFFFFF) << 12;
            for (int z = 0; z < 16; z++) {
                final long yzBits = yBits | ((long) z << 4);
                for (int x = 0; x < 16; x++) {
                    final int globalIndex = globalIndices[flatIdx++];
                    if (globalIndex < 0) {
                        continue;
                    }
                    arr[count++] = ((long) globalIndex << Integer.SIZE) | yzBits | ((long) x << 8);
                    written++;
                }
            }
        }

        instructions.instructionCount = count;
        if (written > 0) {
            instructions.positionMapDirty = true;
            instructions.bulkEpoch++;
        }
    }

    /**
     * Updates only the palette/state of an already pre-filled decoded block instruction.
     *
     * <p>This is intended for default-sparse section decoding where the section baseline
     * was filled in deterministic section order and exception blocks are patched in place
     * by known storage index.</p>
     *
     * <p>This method must not rebuild or consult the position map.</p>
     *
     * @param index     instruction index in storage
     * @param paletteId new palette index/state id
     */
    public void updateDecodedPaletteAtKnownIndex(
            final int index,
            final int paletteId
    ) {
        assert instructions.hasIndex(index) : "Index " + index + " out of bounds";
        instructions.updatePalette(index, paletteId);
    }


    /**
     * Appends a block change for bulk replay/capture paths.
     *
     * <p>The caller is expected to rebuild the block payload from scratch and to
     * guarantee position uniqueness for this pass. This avoids the
     * duplicate-detection work done by {@link #addBlockChange(int, int, int, Object, boolean)}.</p>
     *
     * @param x     local chunk X coordinate
     * @param y     block Y coordinate
     * @param z     local chunk Z coordinate
     * @param state captured block state
     */
    public void appendSnapshotBlockChange(
            final int x,
            final int y,
            final int z,
            final S state
    ) {
        if (state == null) {
            return;
        }

        final int paletteId = blockPalette.getOrAdd(state);
        final long posKey = BlockInstruction.packPos(x, y, z);
        instructions.addAppendOnly(packInstruction(paletteId, posKey));
    }

    /**
     * Adds or updates block entity data and marks the delta dirty.
     *
     * @param x   local chunk X coordinate
     * @param y   block Y coordinate
     * @param z   local chunk Z coordinate
     * @param nbt block entity payload
     */
    public void addBlockEntityData(
            final int x,
            final int y,
            final int z,
            final N nbt
    ) {
        addBlockEntityData(x, y, z, nbt, true);
    }

    /**
     * Adds or updates block entity data.
     *
     * @param x         local chunk X coordinate
     * @param y         block Y coordinate
     * @param z         local chunk Z coordinate
     * @param nbt       block entity payload
     * @param markDirty whether to mark dirty if data changes
     */
    public void addBlockEntityData(
            final int x,
            final int y,
            final int z,
            final N nbt,
            final boolean markDirty
    ) {
        if (nbt == null) {
            return;
        }

        final long key = BlockInstruction.packPos(x, y, z);
        final Long2ObjectOpenHashMap<N> entities = getOrCreateBlockEntities();

        final N existing = entities.get(key);

        if (existing == nbt || nbt.equals(existing)) {
            return;
        }

        entities.put(key, nbt);

        if (markDirty) {
            markDirtyInternal();
        }
    }

    /**
     * Returns all block entity payloads.
     *
     * @return packed-position to block entity payload map
     */
    public Long2ObjectMap<N> getBlockEntities() {
        return blockEntities == null
                ? Long2ObjectMaps.emptyMap()
                : blockEntities;
    }

    /**
     * Clears all block entity payloads.
     *
     * @param markDirty whether to mark dirty when payloads were present
     */
    public void clearBlockEntityPayloads(final boolean markDirty) {
        if (blockEntities == null || blockEntities.isEmpty()) {
            return;
        }

        blockEntities.clear();

        if (markDirty) {
            markDirtyInternal();
        }
    }

    /**
     * Returns the mutable block entity map, allocating it on first use.
     *
     * @return mutable block entity map
     */
    private Long2ObjectOpenHashMap<N> getOrCreateBlockEntities() {
        if (blockEntities == null) {
            blockEntities = new Long2ObjectOpenHashMap<>();
        }

        return blockEntities;
    }

    /**
     * Stores or updates an active entity payload.
     *
     * @param entityId runtime entity id
     * @param nbt      entity payload
     */
    @SuppressWarnings("unused")
    public void putEntity(final int entityId, final N nbt) {
        if (nbt == null) {
            return;
        }

        getOrCreateActiveEntities().put(entityId, nbt);
        markDirtyInternal();
    }

    /**
     * Removes an active entity by runtime id.
     *
     * @param entityId runtime entity id
     */
    @SuppressWarnings("unused")
    public void removeEntity(final int entityId) {
        if (activeEntities != null && activeEntities.remove(entityId) != null) {
            markDirtyInternal();
        }
    }

    /**
     * Clears all active entities.
     */
    @SuppressWarnings("unused")
    public void clearActiveEntities() {
        if (activeEntities != null && !activeEntities.isEmpty()) {
            activeEntities.clear();
            markDirtyInternal();
        }
    }

    /**
     * Adds an entity payload to the pending spawn list.
     *
     * @param nbt entity payload
     */
    public void addPendingEntity(final N nbt) {
        if (nbt == null) {
            return;
        }

        if (pendingEntities.isEmpty()) {
            pendingEntities = new ArrayList<>(1);
        }

        pendingEntities.add(nbt);
        markDirtyInternal();
    }

    /**
     * Replaces pending entities.
     *
     * @param newEntities new pending entity list
     * @param markDirty   whether to mark dirty if the list changed
     */
    public void setEntities(final List<N> newEntities, final boolean markDirty) {
        final List<N> safeEntities = normalizeEntityList(newEntities);

        if (!markDirty) {
            pendingEntities = safeEntities;
            return;
        }

        if (pendingEntities.equals(safeEntities)) {
            return;
        }

        pendingEntities = safeEntities;
        markDirtyInternal();
    }

    /**
     * Returns active and pending entity payloads as one materialized list.
     *
     * @return combined entity payload list
     */
    public List<N> getEntitiesList() {
        final int activeSize = activeEntities == null ? 0 : activeEntities.size();
        final int pendingSize = pendingEntities.size();
        final List<N> allEntities = new ArrayList<>(activeSize + pendingSize);

        if (activeEntities != null) {
            allEntities.addAll(activeEntities.values());
        }

        allEntities.addAll(pendingEntities);

        return allEntities;
    }

    /**
     * Counts non-null entity payloads without materializing a merged list.
     *
     * @return number of non-null entity payloads
     */
    public int countNonNullEntities() {
        return ChunkDeltaViews.countNonNullEntities(activeEntities, pendingEntities);
    }

    /**
     * Counts non-null pending entity payloads only.
     *
     * @return number of non-null pending entity payloads
     */
    public int countPendingEntities() {
        int count = 0;
        for (final N nbt : pendingEntities) {
            if (nbt != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Visits entity payloads in active-then-pending order.
     *
     * @param consumer entity payload consumer
     */
    public void forEachEntity(final Consumer<? super N> consumer) {
        ChunkDeltaViews.forEachEntity(activeEntities, pendingEntities, consumer);
    }

    /**
     * Visits pending entity payloads only.
     *
     * @param consumer entity payload consumer
     */
    public void forEachPendingEntity(final Consumer<? super N> consumer) {
        Objects.requireNonNull(consumer, "consumer");

        for (final N nbt : pendingEntities) {
            consumer.accept(nbt);
        }
    }

    /**
     * Clears pending entities.
     */
    public void clearPendingEntities() {
        if (!pendingEntities.isEmpty()) {
            pendingEntities = Collections.emptyList();
            markDirtyInternal();
        }
    }

    /**
     * Removes pending entity payloads matching {@code predicate}.
     *
     * @param predicate pending entity payload matcher
     */
    public void removePendingEntitiesMatching(final Predicate<? super N> predicate) {
        Objects.requireNonNull(predicate, "predicate");

        if (pendingEntities.isEmpty()) {
            return;
        }

        final List<N> filtered = new ArrayList<>(pendingEntities.size());
        for (final N nbt : pendingEntities) {
            if (!predicate.test(nbt)) {
                filtered.add(nbt);
            }
        }
        if (filtered.size() != pendingEntities.size()) {
            pendingEntities = filtered.isEmpty() ? Collections.emptyList() : filtered;
            markDirtyInternal();
        }
    }

    /**
     * Removes entity payloads matching {@code predicate} from both active and pending sets.
     *
     * @param predicate entity payload matcher
     */
    public void removeEntitiesMatching(final Predicate<? super N> predicate) {
        Objects.requireNonNull(predicate, "predicate");

        boolean changed = false;
        if (activeEntities != null && !activeEntities.isEmpty()) {
            changed = activeEntities.values()
                    .removeIf(predicate);
        }
        if (!pendingEntities.isEmpty()) {
            final List<N> filtered = new ArrayList<>(pendingEntities.size());
            for (final N nbt : pendingEntities) {
                if (!predicate.test(nbt)) {
                    filtered.add(nbt);
                }
            }
            if (filtered.size() != pendingEntities.size()) {
                pendingEntities = filtered.isEmpty() ? Collections.emptyList() : filtered;
                changed = true;
            }
        }
        if (changed) {
            markDirtyInternal();
        }
    }

    /**
     * Returns the mutable active entity map, allocating it on first use.
     *
     * @return mutable active entity map
     */
    private Int2ObjectOpenHashMap<N> getOrCreateActiveEntities() {
        if (activeEntities == null) {
            activeEntities = new Int2ObjectOpenHashMap<>();
        }

        return activeEntities;
    }

    /**
     * Returns chunk-level metadata.
     *
     * @return metadata payload, or {@code null}
     */
    public N getChunkMetadata() {
        return chunkMetadata;
    }

    /**
     * Sets chunk-level metadata and marks dirty if it changed.
     *
     * @param metadata metadata payload
     */
    public void setChunkMetadata(final N metadata) {
        setChunkMetadata(metadata, true);
    }

    /**
     * Sets chunk-level metadata.
     *
     * @param metadata  metadata payload
     * @param markDirty whether to mark dirty if changed
     */
    public void setChunkMetadata(final N metadata, final boolean markDirty) {
        // Only a dirtying set needs equal content to stay quiet; a quiet one compared every save's metadata,
        // stored light included, in full just to keep an encoding cache the save worker rebuilds cheaply.
        if (markDirty ? Objects.equals(chunkMetadata, metadata) : chunkMetadata == metadata) {
            return;
        }

        chunkMetadata = metadata;
        encodedChunkMetadata = null;
        encodedChunkMetadataHash = 0;

        if (markDirty) {
            markDirtyInternal();
        }
    }

    /**
     * Updates chunk metadata using a pre-encoded payload.
     *
     * <p>The delta is marked dirty only when the encoded bytes change. The encoded
     * payload is copied because callers may reuse or mutate their buffer.</p>
     *
     * @param metadata       metadata object to retain
     * @param encodedPayload serialized payload including its length prefix
     * @return {@code true} if metadata changed
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean updateChunkMetadataWithEncodedPayload(
            final N metadata,
            final byte[] encodedPayload
    ) {
        if (metadata == null || encodedPayload == null) {
            if (chunkMetadata == null && encodedChunkMetadata == null) {
                return false;
            }

            setChunkMetadata(metadata, true);
            return true;
        }

        final int newHash = Arrays.hashCode(encodedPayload);

        if (encodedChunkMetadata != null
                && encodedChunkMetadataHash == newHash
                && Arrays.equals(encodedChunkMetadata, encodedPayload)) {
            return false;
        }

        chunkMetadata = metadata;
        encodedChunkMetadata = Arrays.copyOf(encodedPayload, encodedPayload.length);
        encodedChunkMetadataHash = newHash;
        markDirtyInternal();

        return true;
    }

    /**
     * Returns cached encoded metadata payload, if available.
     *
     * <p>The internal byte array is returned directly for performance. Callers must
     * treat it as read-only.</p>
     *
     * @return encoded metadata payload, or {@code null}
     */
    public byte[] getEncodedChunkMetadata() {
        return encodedChunkMetadata;
    }

    /**
     * Caches an encoded metadata payload.
     *
     * @param encodedPayload encoded payload to cache, or {@code null} to clear
     */
    public void cacheEncodedChunkMetadata(final byte[] encodedPayload) {
        if (encodedPayload == null) {
            encodedChunkMetadata = null;
            encodedChunkMetadataHash = 0;
            return;
        }

        encodedChunkMetadata = Arrays.copyOf(encodedPayload, encodedPayload.length);
        encodedChunkMetadataHash = Arrays.hashCode(encodedPayload);
    }

    /**
     * Materializes block instructions as objects.
     *
     * <p>This allocates one {@link BlockInstruction} per block change and should
     * be avoided on hot paths. Prefer {@link #forEachBlock(BlockVisitor)} when
     * possible.</p>
     *
     * @return block instruction list
     */
    public List<BlockInstruction> getBlockInstructions() {
        final List<BlockInstruction> list = new ArrayList<>(instructions.instructionCount);

        for (int i = 0; i < instructions.instructionCount; i++) {
            list.add(BlockInstruction.fromPacked(instructions.packedInstructions[i]));
        }

        return list;
    }

    /**
     * Returns the number of stored block changes without materializing instructions.
     *
     * @return block change count
     */
    public int getBlockChangesCount() {
        return instructions.instructionCount;
    }

    /**
     * Returns the number of unique sections touched by block changes or block entities.
     *
     * @return touched section count
     */
    public int getTouchedSectionCount() {
        return ChunkDeltaViews.touchedSectionCount(instructions.packedInstructions, instructions.instructionCount,
                blockEntities);
    }

    /**
     * Returns the block palette.
     *
     * @return block palette
     */
    public Palette<S> getBlockPalette() {
        return blockPalette;
    }

    /**
     * Returns whether this delta has no payload.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return ChunkDeltaViews.isEmpty(
                instructions.instructionCount,
                blockEntities,
                activeEntities,
                pendingEntities,
                chunkMetadata
        );
    }

    /**
     * Visits block changes only.
     *
     * <p>This avoids materializing {@link BlockInstruction} objects and is the
     * preferred hot-path block iteration API.</p>
     *
     * @param visitor block visitor
     */
    public void forEachBlock(final BlockVisitor<S> visitor) {
        ChunkDeltaViews.forEachPackedBlock(
                instructions.packedInstructions,
                instructions.instructionCount,
                blockPalette,
                visitor
        );
    }

    /**
     * Visits sparse block changes together with their palette ids.
     *
     * <p>This avoids a second palette lookup on hot paths that need to copy the
     * encoded instruction shape elsewhere, such as restore-time runtime-delta
     * reconstruction.</p>
     *
     * @param visitor block visitor receiving coordinates, palette id, and state
     */
    public void forEachBlockInstruction(final BlockInstructionVisitor<S> visitor) {
        Objects.requireNonNull(visitor, "visitor");

        for (int i = 0; i < instructions.instructionCount; i++) {
            final long instruction = instructions.packedInstructions[i];
            final int paletteIndex = (int) (instruction >>> Integer.SIZE);
            final S state = blockPalette.get(paletteIndex);

            if (state == null) {
                continue;
            }

            final long posKey = instruction & POSITION_MASK;
            visitor.visitBlock(
                    BlockInstruction.unpackX(posKey),
                    BlockInstruction.unpackY(posKey),
                    BlockInstruction.unpackZ(posKey),
                    paletteIndex,
                    state
            );
        }
    }

    /**
     * Returns whether this delta has unsaved changes.
     *
     * @return {@code true} if dirty
     */
    public boolean isDirty() {
        return ownershipState.mutationGeneration != ownershipState.savedGeneration;
    }

    /**
     * Marks this delta dirty.
     */
    public void markDirty() {
        markDirtyInternal();
    }

    /**
     * Marks this delta dirty and records the caller-provided mutation source.
     *
     * @param source mutation source label for trace/debug attribution
     */
    public void markDirty(final String source) {
        prepareForMutation(source);
        markDirtyInternal();
    }

    /**
     * Marks this delta dirty only if it is currently clean.
     *
     * @param source mutation source label for trace/debug attribution
     * @return {@code true} if the delta transitioned from clean to dirty
     */
    public boolean markDirtyIfClean(final String source) {
        if (isDirty()) {
            return false;
        }

        prepareForMutation(source);
        markDirtyInternal();
        return true;
    }

    /**
     * Marks this delta saved.
     *
     * <p>The trace call is guarded by {@link ChunkisDebugConfig#allows} to avoid
     * allocating a {@link io.liparakis.chunkis.debug.model.ChunkTraceEvent} on
     * every save when debug tracing is disabled (the common production path).</p>
     */
    public void markSaved() {
        final boolean wasDirty = isDirty();
        ownershipState.savedGeneration = ownershipState.mutationGeneration;
        if (wasDirty) {
            traceDirtyTransition(false);
        }
    }

    /**
     * Marks this delta saved only if no newer mutation has happened since the
     * given generation was captured.
     *
     * <p>The trace call is guarded by {@link ChunkisDebugConfig#allows} to avoid
     * allocating a {@link io.liparakis.chunkis.debug.model.ChunkTraceEvent} on
     * every save when debug tracing is disabled (the common production path).</p>
     *
     * @param generation generation that was persisted
     * @return {@code true} if the save state was updated
     */
    public boolean markSavedIfGeneration(final long generation) {
        if (ownershipState.mutationGeneration != generation) {
            return false;
        }

        final boolean wasDirty = isDirty();
        ownershipState.savedGeneration = generation;
        if (wasDirty) {
            traceDirtyTransition(false);
        }
        return true;
    }

    /**
     * Returns the current mutation generation.
     *
     * @return mutation generation
     */
    public long getMutationGeneration() {
        return ownershipState.mutationGeneration;
    }

    /**
     * Returns the CIS source format version.
     *
     * @return source CIS version
     */
    public int getSourceVersion() {
        return sourceVersion;
    }

    /**
     * Sets the CIS source format version.
     *
     * @param sourceVersion source CIS version
     */
    public void setSourceVersion(final int sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    /**
     * Returns whether restored loads should suppress one-time vanilla repopulation.
     *
     * @return {@code true} if suppression is enabled
     */
    public boolean shouldSuppressInitialRepopulation() {
        return suppressInitialRepopulation;
    }

    /**
     * Sets replay-time repopulation suppression.
     *
     * @param suppressInitialRepopulation {@code true} to suppress repopulation
     */
    public void setSuppressInitialRepopulation(final boolean suppressInitialRepopulation) {
        this.suppressInitialRepopulation = suppressInitialRepopulation;
    }

    /**
     * Claims ownership metadata for this delta if no claim has been recorded yet.
     *
     * @param reason ownership reason stored with the delta
     * @param source caller/source claiming ownership
     * @return {@code true} if the claim was stored
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean claimOwnership(final String reason, final String source) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        if (ownershipState.ownershipReason != null) {
            return false;
        }
        ownershipState.ownershipReason = reason;
        ownershipState.ownershipSource = source;
        return true;
    }

    /**
     * Returns whether this delta already has ownership metadata attached.
     *
     * @return {@code true} if ownership has been claimed
     */
    public boolean hasOwnershipClaim() {
        return ownershipState.ownershipReason != null && !ownershipState.ownershipReason.isBlank();
    }

    /**
     * Returns the stored ownership reason.
     *
     * @return ownership reason, or {@code null}
     */
    public String getOwnershipReason() {
        return ownershipState.ownershipReason;
    }

    /**
     * Returns the source that claimed ownership.
     *
     * @return ownership source, or {@code null}
     */
    public String getOwnershipSource() {
        return ownershipState.ownershipSource;
    }

    /**
     * Returns the first recorded mutation source after the delta became dirty.
     *
     * @return first mutation source, or {@code null}
     */
    public String getFirstMutationSource() {
        return ownershipState.firstMutationSource;
    }

    /**
     * Stashes a source label to be consumed by the next dirtying mutation.
     *
     * @param source mutation source label
     */
    public void prepareForMutation(final String source) {
        ownershipState.pendingMutationSource = source;
    }

    /**
     * Visitor for all delta contents.
     */
    public interface DeltaVisitor<S, N> {

        /**
         * Performs visit block.
         */
        void visitBlock(int x, int y, int z, S state);

        /**
         * Performs visit block entity.
         */
        void visitBlockEntity(int x, int y, int z, N nbt);

        /**
         * Performs visit entity.
         */
        void visitEntity(N nbt);
    }

    /**
     * Visitor for block-only scans.
     */
    @FunctionalInterface
    public interface BlockVisitor<S> {

        /**
         * Performs visit block.
         */
        void visitBlock(int x, int y, int z, S state);
    }

    /**
     * Visitor for sparse block scans that also need the encoded palette id.
     */
    @FunctionalInterface
    public interface BlockInstructionVisitor<S> {

        /**
         * Performs visit block.
         */
        void visitBlock(int x, int y, int z, int paletteId, S state);
    }
}
