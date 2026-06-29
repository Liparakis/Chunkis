package io.liparakis.chunkis.core;

import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.storage.model.CisConstants;
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
 * @author Liparakis
 * @version 1.1
 * @see BlockInstruction
 * @see Palette
 */
public final class ChunkDelta<S, N> {

    private static final String SOURCE = "ChunkDelta";

    /**
     * Initial block instruction capacity.
     */
    private static final int INITIAL_CAPACITY = 64;

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
     * Private constructor used by snapshot creation to initialize final fields.
     */
    private ChunkDelta(
            final Palette<S> blockPalette,
            final Predicate<S> isEmptyState
                      ) {
        this.blockPalette = blockPalette;
        this.isEmptyState = isEmptyState;
        this.instructions = new BlockInstructionStorage();
        this.ownershipState = new DeltaOwnershipState();
        this.pendingEntities = Collections.emptyList();
        this.sourceVersion = CisConstants.VERSION;
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
     * Extracts the packed local position from an instruction.
     *
     * @param instruction packed instruction
     * @return packed local position
     */
    private static long instructionPosKey(final long instruction) {
        return instruction & POSITION_MASK;
    }

    /**
     * Extracts the palette id from an instruction.
     *
     * @param instruction packed instruction
     * @return palette id
     */
    private static int instructionPaletteId(final long instruction) {
        return (int) (instruction >>> Integer.SIZE);
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
     * Creates a snapshot of this delta, copying payload values with the supplied
     * copier.
     *
     * <p>Container state is copied by this method. The caller owns the payload
     * copy policy because core does not know whether {@code N} is immutable. For
     * Minecraft NBT payloads, pass {@code NbtCompound::copy}.</p>
     *
     * @param payloadCopier copies each stored payload value
     * @return a new ChunkDelta instance representing this delta's current state
     */
    public ChunkDelta<S, N> snapshot(final UnaryOperator<N> payloadCopier) {
        Objects.requireNonNull(payloadCopier, "payloadCopier");

        final ChunkDelta<S, N> snap = new ChunkDelta<>(
                this.blockPalette.copy(),
                this.isEmptyState
        );

        snap.instructions.packedInstructions = Arrays.copyOf(this.instructions.packedInstructions, this.instructions.packedInstructions.length);
        snap.instructions.instructionCount = this.instructions.instructionCount;
        snap.instructions.positionMap.clear();
        snap.instructions.positionMap.putAll(this.instructions.positionMap);

        if (this.blockEntities != null) {
            snap.blockEntities = new Long2ObjectOpenHashMap<>(this.blockEntities.size());
            for (final Long2ObjectMap.Entry<N> entry : this.blockEntities.long2ObjectEntrySet()) {
                snap.blockEntities.put(entry.getLongKey(), payloadCopier.apply(entry.getValue()));
            }
        }

        if (this.activeEntities != null) {
            snap.activeEntities = new Int2ObjectOpenHashMap<>(this.activeEntities.size());
            for (final it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<N> entry
                    : this.activeEntities.int2ObjectEntrySet()) {
                snap.activeEntities.put(entry.getIntKey(), payloadCopier.apply(entry.getValue()));
            }
        }

        if (!this.pendingEntities.isEmpty()) {
            snap.pendingEntities = new ArrayList<>(this.pendingEntities.size());
            for (final N payload : this.pendingEntities) {
                snap.pendingEntities.add(payloadCopier.apply(payload));
            }
        }

        snap.chunkMetadata = this.chunkMetadata != null
                ? payloadCopier.apply(this.chunkMetadata)
                : null;

        if (this.encodedChunkMetadata != null) {
            snap.encodedChunkMetadata = Arrays.copyOf(this.encodedChunkMetadata, this.encodedChunkMetadata.length);
            snap.encodedChunkMetadataHash = this.encodedChunkMetadataHash;
        }

        snap.ownershipState.mutationGeneration = this.ownershipState.mutationGeneration;
        snap.ownershipState.savedGeneration = this.ownershipState.savedGeneration;
        snap.sourceVersion = this.sourceVersion;
        snap.suppressInitialRepopulation = this.suppressInitialRepopulation;
        snap.ownershipState.ownershipReason = this.ownershipState.ownershipReason;
        snap.ownershipState.ownershipSource = this.ownershipState.ownershipSource;
        snap.ownershipState.firstMutationSource = this.ownershipState.firstMutationSource;

        return snap;
    }

    /**
     * Records one semantic mutation.
     */
    private void markDirtyInternal() {
        final boolean wasDirty = isDirty();
        ownershipState.mutationGeneration++;
        if (!wasDirty) {
            if (ownershipState.firstMutationSource == null) {
                ownershipState.firstMutationSource =
                        ownershipState.pendingMutationSource != null ? ownershipState.pendingMutationSource : SOURCE;
            }
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.DIRTY_TRACKING,
                    ChunkTraceEventType.DELTA_MARKED_DIRTY,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.DELTA_BECAME_DIRTY,
                    SOURCE,
                    "delta became dirty",
                    null,
                    null,
                    null,
                    null,
                    true,
                    null
                                 );
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
        final int existingIndex = instructions.positionMap.get(posKey);

        if (existingIndex != -1) {
            updateExistingInstruction(paletteId, posKey, existingIndex, markDirty);
        } else {
            addNewInstruction(paletteId, posKey, markDirty);
        }

        cleanupBlockEntityIfEmpty(newState, posKey);
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

        if (markDirty) {
            markDirtyInternal();
        }
    }

    /**
     * Removes block entity payload if the new state is considered empty.
     *
     * @param state  new block state
     * @param posKey packed local position
     */
    private void cleanupBlockEntityIfEmpty(final S state, final long posKey) {
        if (blockEntities != null && isEmptyState.test(state)) {
            blockEntities.remove(posKey);
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

        if (blockEntities.remove(posKey) != null && markDirty) {
            markDirtyInternal();
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
     * Pre-sizes block instruction storage for bulk decode paths.
     *
     * @param additionalBlocks number of additional block instructions expected
     */
    public void ensureBlockCapacity(final int additionalBlocks) {
        instructions.ensureBlockCapacity(additionalBlocks);
    }

    /**
     * Appends a decoded block instruction.
     *
     * <p>The caller is expected to have validated the palette id and position
     * uniqueness. This method still guards capacity because it is public API.</p>
     *
     * @param x         local chunk X coordinate
     * @param y         block Y coordinate
     * @param z         local chunk Z coordinate
     * @param paletteId decoded palette id
     */
    public void appendDecodedBlock(
            final int x,
            final int y,
            final int z,
            final int paletteId
                                  ) {
        final long posKey = BlockInstruction.packPos(x, y, z);
        instructions.add(packInstruction(paletteId, posKey), posKey);
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
        int count = 0;

        if (activeEntities != null) {
            for (final N nbt : activeEntities.values()) {
                if (nbt != null) {
                    count++;
                }
            }
        }

        for (final N nbt : pendingEntities) {
            if (nbt != null) {
                count++;
            }
        }

        return count;
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
        Objects.requireNonNull(consumer, "consumer");

        if (activeEntities != null) {
            for (final N nbt : activeEntities.values()) {
                consumer.accept(nbt);
            }
        }

        for (final N nbt : pendingEntities) {
            consumer.accept(nbt);
        }
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
            changed = activeEntities.values().removeIf(predicate);
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
        if (Objects.equals(chunkMetadata, metadata)) {
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
        return instructions.instructionCount == 0
                && (blockEntities == null || blockEntities.isEmpty())
                && (activeEntities == null || activeEntities.isEmpty())
                && pendingEntities.isEmpty()
                && chunkMetadata == null;
    }

    /**
     * Visits blocks, block entities, and entities.
     *
     * @param visitor visitor to receive delta contents
     */
    public void accept(final DeltaVisitor<S, N> visitor) {
        Objects.requireNonNull(visitor, "visitor");

        forEachBlock(visitor::visitBlock);

        if (blockEntities != null && !blockEntities.isEmpty()) {
            for (final Long2ObjectMap.Entry<N> entry : blockEntities.long2ObjectEntrySet()) {
                final long posKey = entry.getLongKey();

                visitor.visitBlockEntity(
                        BlockInstruction.unpackX(posKey),
                        BlockInstruction.unpackY(posKey),
                        BlockInstruction.unpackZ(posKey),
                        entry.getValue()
                                        );
            }
        }

        if (activeEntities != null) {
            for (final N nbt : activeEntities.values()) {
                visitor.visitEntity(nbt);
            }
        }

        for (final N nbt : pendingEntities) {
            visitor.visitEntity(nbt);
        }
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
        Objects.requireNonNull(visitor, "visitor");

        for (int i = 0; i < instructions.instructionCount; i++) {
            final long instruction = instructions.packedInstructions[i];
            final int paletteIndex = instructionPaletteId(instruction);
            final S state = blockPalette.get(paletteIndex);

            if (state == null) {
                continue;
            }

            final long posKey = instructionPosKey(instruction);

            visitor.visitBlock(
                    BlockInstruction.unpackX(posKey),
                    BlockInstruction.unpackY(posKey),
                    BlockInstruction.unpackZ(posKey),
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

    public void markDirty(final String source) {
        prepareForMutation(source);
        markDirtyInternal();
    }

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
     */
    public void markSaved() {
        final boolean wasDirty = isDirty();
        ownershipState.savedGeneration = ownershipState.mutationGeneration;
        if (wasDirty) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.DIRTY_TRACKING,
                    ChunkTraceEventType.DELTA_MARKED_CLEAN,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.DELTA_MARKED_SAVED,
                    SOURCE,
                    "delta marked clean",
                    null,
                    null,
                    null,
                    null,
                    false,
                    null
                                 );
        }
    }

    /**
     * Marks this delta saved only if no newer mutation has happened since the
     * given generation was captured.
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
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.DIRTY_TRACKING,
                    ChunkTraceEventType.DELTA_MARKED_CLEAN,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.DELTA_MARKED_SAVED,
                    SOURCE,
                    "delta marked clean",
                    null,
                    null,
                    null,
                    null,
                    false,
                    null
                                 );
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

    public boolean hasOwnershipClaim() {
        return ownershipState.ownershipReason != null && !ownershipState.ownershipReason.isBlank();
    }

    public String getOwnershipReason() {
        return ownershipState.ownershipReason;
    }

    public String getOwnershipSource() {
        return ownershipState.ownershipSource;
    }

    public String getFirstMutationSource() {
        return ownershipState.firstMutationSource;
    }

    public void prepareForMutation(final String source) {
        ownershipState.pendingMutationSource = source;
    }

    /**
     * Visitor for all delta contents.
     */
    public interface DeltaVisitor<S, N> {

        void visitBlock(int x, int y, int z, S state);

        void visitBlockEntity(int x, int y, int z, N nbt);

        void visitEntity(N nbt);
    }

    /**
     * Visitor for block-only scans.
     */
    @FunctionalInterface
    public interface BlockVisitor<S> {

        void visitBlock(int x, int y, int z, S state);
    }
}
