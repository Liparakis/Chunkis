package io.liparakis.chunkis.world.restoration.core;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.perf.ServerHotpathMetrics;
import io.liparakis.chunkis.debug.trace.ChunkTraceInvariants;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.ChunkSectionDebugUtil;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.world.entity.replay.EntityReplayCoordinator;
import io.liparakis.chunkis.world.restoration.capture.CisSnapshotCapture;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.restoration.nbt.PreloadedBlockSections;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Utility for restoring chunks from Chunkis delta data.
 *
 * <p>Restoration applies an authoritative CIS snapshot from a {@link ChunkDelta} to
 * a freshly generated {@link WorldChunk}. Missing block entries mean air.</p>
 *
 * <h2>Restoration process</h2>
 * <ol>
 *   <li>clear the target chunk block grid to air</li>
 *   <li>remove stale block-entity state</li>
 *   <li>apply saved block changes directly to chunk sections</li>
 *   <li>restore compatible block entities from NBT</li>
 *   <li>replay legacy entity payloads only when the delta still owns entity persistence</li>
 *   <li>copy validated restored data into the runtime delta without marking it dirty</li>
 * </ol>
 *
 * <p><b>Threading:</b> all methods must run on the server thread. Entity spawning,
 * block entity mutation, and chunk section writes are not thread-safe.</p>
 */
public final class ChunkRestorer {

    /**
     * Minimum average restored blocks per touched section before refresh switches to bulk mode.
     */
    private static final int BULK_REFRESH_BLOCKS_PER_SECTION_THRESHOLD = 768;
    /**
     * Minimum number of chunk-local columns that must be touched before full bulk rebuild is worthwhile.
     */
    private static final int BULK_REFRESH_TOUCHED_COLUMNS_THRESHOLD = 96;

    /**
     * Opacity of a block no light passes through.
     */
    private static final int MAX_OPACITY = 15;

    /**
     * Log source tag identifier mapping for restoration operations.
     */
    private static final String RESTORE_SOURCE = "ChunkRestorer#restore";

    /**
     * NBT key mapping identifying entities/block entities.
     */
    private static final String ID_KEY = "id";

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkRestorer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Restores a chunk from persisted delta data.
     *
     * <p>Saved block entries are replayed and copied to the runtime delta. The
     * runtime delta is populated in silent mode because restoration itself is not a
     * new player edit.</p>
     *
     * <p>This method intentionally no longer optimizes away saved block entries
     * that happen to match freshly generated terrain. The generated baseline can
     * change across Minecraft versions, datapacks, and worldgen changes, so pruning
     * here would make persistence depend on a moving target.</p>
     *
     * @param world        server world context
     * @param chunk        chunk to restore into
     * @param protoDelta   source delta loaded from disk/proto state
     * @param runtimeDelta runtime delta to populate with validated changes
     */
    public static void restore(final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta) {
        restore(world, chunk, protoDelta, runtimeDelta, null);
    }

    /**
     * Restores a chunk from persisted delta data using trace operations identifiers.
     *
     * @param world        server world context
     * @param chunk        chunk to restore into
     * @param protoDelta   source delta loaded from disk/proto state
     * @param runtimeDelta runtime delta to populate with validated changes
     * @param operationId  optional execution/operation trace ID label, may be null
     */
    public static void restore(final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final String operationId) {
        restore(world, chunk, protoDelta, runtimeDelta, operationId, false);
    }

    /**
     * Restores a chunk from persisted delta data, optionally leaving block entities for vanilla to build.
     *
     * <p>A chunk vanilla has just converted and not yet post-processed builds pending block entities itself, in
     * {@code WorldChunk#runPostProcessing} or on first access, as it does for its own loads; handing them over
     * keeps their decoding off the load path. Any other chunk gets them built here.</p>
     *
     * @param world                  server world context
     * @param chunk                  chunk to restore into
     * @param protoDelta             source delta loaded from disk/proto state
     * @param runtimeDelta           runtime delta to populate with validated changes
     * @param operationId            optional execution/operation trace ID label, may be null
     * @param freshlyConvertedChunk  whether vanilla has yet to post-process {@code chunk}
     */
    public static void restore(final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final String operationId,
            final boolean freshlyConvertedChunk) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(protoDelta, "protoDelta");
        if (protoDelta.isBlockPayloadReleased()) {
            // Its blocks live only in another loaded chunk and on disk; restoring it would clear this chunk.
            throw new IllegalStateException("Chunkis: Refusing to restore chunk " + chunk.getPos()
                    + " from a delta whose block payload was released");
        }

        final ChunkPos chunkPos = chunk.getPos();
        final boolean hasPersistedBaseChunk = CisNbtUtil.hasPersistedBaseChunkNbt(protoDelta.getChunkMetadata());
        final boolean usePersistedBaseChunkForBlocks = CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(protoDelta.getChunkMetadata());
        final boolean invalidBlockEntityOnlyPayloadWithoutBase = ChunkTraceInvariants.hasInvalidBlockEntityOnlyPayloadWithoutBase(
                protoDelta,
                hasPersistedBaseChunk);

        if (ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO)) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.RESTORE_TX_START,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    RESTORE_SOURCE,
                    "starting restore with decoded payload: " + describeReplayPayload(protoDelta) + ", baseChunkNbt="
                            + (usePersistedBaseChunkForBlocks ? "used"
                            : hasPersistedBaseChunk ? "metadata-only" : "missing"),
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null);
        }
        PayloadWatchTracer.traceRestoreStarted(world, chunkPos, chunk, protoDelta, operationId);
        if (usePersistedBaseChunkForBlocks) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.SNAPSHOT_BACKED_RESTORE,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    RESTORE_SOURCE,
                    "restoring chunk from persisted base snapshot plus sparse delta",
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null);
        }
        if (invalidBlockEntityOnlyPayloadWithoutBase) {
            ChunkTraceStore.trace(ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    RESTORE_SOURCE,
                    "blockEntities without blockChanges require persisted base chunk NBT",
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null);
            ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.RESTORE_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    RESTORE_SOURCE,
                    "skipped restore because sparse block-entity payload had no base snapshot",
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null);
            return;
        }

        // The load NBT already carried exactly these blocks, parsed by vanilla off the server thread.
        final boolean blocksPreloaded = PreloadedBlockSections.consume(protoDelta);
        final long[] loadedContentPrint = PreloadedBlockSections.takeContentPrint(protoDelta);
        // Set when the load rebuilt the delta from its stored entry plus a released delta's edits: its only block
        // changes the entry lacks are those edits.
        final ChunkDeltaView<BlockState, NbtCompound> storedEntryEdits = protoDelta.takeStoredEntryEdits();
        // A clean preloaded baseline is released as soon as the restore marks the runtime delta saved, and a
        // rebuilt one released keeping its edits, so copying its instructions would only be undone.
        final boolean releaseAfterFinish = blocksPreloaded && runtimeDelta != null
                && (!protoDelta.isDirty() || storedEntryEdits != null)
                && !usePersistedBaseChunkForBlocks && CisNbtUtil.hasFullBlockBaseline(protoDelta.getChunkMetadata());

        // Reset the runtime delta only once the payload is known to be restorable; resetting it
        // before the early return above would leave an empty delta that could overwrite storage.
        if (runtimeDelta != null) {
            runtimeDelta.clearBlockPayloads(false);
            runtimeDelta.clearBlockEntityPayloads(false);
            runtimeDelta.clearActiveEntities();
            runtimeDelta.setEntities(List.of(), false);
            runtimeDelta.setChunkMetadata(protoDelta.getChunkMetadata(), false);
            runtimeDelta.setSuppressInitialRepopulation(protoDelta.shouldSuppressInitialRepopulation());
            runtimeDelta.copyBlockPaletteFrom(protoDelta);
            if (!releaseAfterFinish) {
                runtimeDelta.ensureBlockCapacity(protoDelta.getBlockChangesCount());
            }
            seedMigratedAuthoritativeBlockEntities(protoDelta, runtimeDelta);
        }
        final ChunkRestorationVisitor visitor = new ChunkRestorationVisitor(world,
                chunk,
                protoDelta,
                runtimeDelta,
                operationId,
                freshlyConvertedChunk);

        // Only the light diff of a written restore reads this copy.
        @SuppressWarnings("unchecked")
        final PalettedContainer<BlockState>[] blocksBefore = blocksPreloaded
                ? new PalettedContainer[0]
                : copySectionBlocks(chunk);
        try {
            if (!usePersistedBaseChunkForBlocks && !blocksPreloaded) {
                ChunkRestoreBlockOperations.clearChunkToAir(chunk);
            }
            visitor.cleanupReplayedEntities(protoDelta);
            if (blocksPreloaded) {
                visitor.acceptPreloadedBlocks(protoDelta);
                if (runtimeDelta != null && !releaseAfterFinish) {
                    runtimeDelta.appendDecodedBlocksFrom(protoDelta);
                }
            } else {
                // ponytail: keep restore traversal explicit so the hot block path skips generic delta dispatch.
                if (runtimeDelta != null) {
                    protoDelta.forEachBlockInstruction(visitor);
                } else {
                    protoDelta.forEachBlock(visitor::visitBlock);
                }
                visitor.recalculateTouchedSectionCounts();
            }
            protoDelta.getBlockEntities()
                    .forEach((packedPos, nbt) -> visitor.visitBlockEntity(io.liparakis.chunkis.core.BlockInstruction.unpackX(
                                    packedPos),
                            io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos),
                            io.liparakis.chunkis.core.BlockInstruction.unpackZ(packedPos),
                            nbt));
            if (visitor.replayLegacyEntities()) {
                protoDelta.forEachEntity(visitor::visitEntity);
            }
            visitor.finishRestoration(blocksBefore);
            if (releaseAfterFinish && storedEntryEdits != null) {
                // The stored entry plus the edits are exactly these blocks; dirty only for the delta's other
                // unsaved changes.
                runtimeDelta.releaseBlockPayloadKeeping(storedEntryEdits);
            } else if (releaseAfterFinish) {
                // Clean here: finishRestoration marks the runtime delta saved for a clean source.
                runtimeDelta.releaseBlockPayload();
                // The chunk holds exactly the stored entry's blocks: a later save that still finds them may copy
                // the entry's block part instead of encoding them again.
                final long[] storedBlockPart = protoDelta.getStoredBlockPartHash();
                if (loadedContentPrint != null && storedBlockPart != null) {
                    runtimeDelta.setWrittenBlocksPrint(new long[]{loadedContentPrint[0], loadedContentPrint[1],
                            storedBlockPart[0], storedBlockPart[1]});
                }
            }
            final boolean holdsExactBaseline = runtimeDelta != null && !usePersistedBaseChunkForBlocks
                    && CisNbtUtil.hasFullBlockBaseline(protoDelta.getChunkMetadata())
                    && visitor.blockApplyFailureCounters().appliedEveryInstruction();
            replayPendingEntitiesIfNeeded(world, chunk, runtimeDelta, operationId);
            if (runtimeDelta != null && runtimeDelta.shouldSuppressInitialRepopulation()
                    && EntityReplayCoordinator.allPendingEntitiesVisible(world, chunk.getPos(), runtimeDelta)) {
                runtimeDelta.clearPendingEntities();
            }
            // A delta released after finishing stays released even if entity replay made it dirty since; a save
            // then captures its blocks from the chunk.
            if (!releaseAfterFinish && holdsExactBaseline && !runtimeDelta.isDirty()) {
                // The loaded chunk holds exactly the stored blocks; keeping them again as instructions would
                // double them in memory. The next save after an edit captures them back from the chunk.
                runtimeDelta.releaseBlockPayload();
            } else if (!releaseAfterFinish && holdsExactBaseline) {
                // The chunk now holds exactly the restored baseline, so the first save can skip unchanged sections.
                CisSnapshotCapture.recordRestoredBaseline(chunk, runtimeDelta);
            }
        } catch (final RuntimeException e) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.RESTORE_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.RESTORE_EXCEPTION,
                    RESTORE_SOURCE,
                    "restore failed with exception",
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    null,
                    null);
            throw e;
        }

        final int appliedCount = visitor.appliedBlocksCount() + visitor.restoredBlockEntitiesCount();
        final boolean restoreEmptyResult = ChunkTraceInvariants.shouldReportRestoreEmptyResult(protoDelta,
                appliedCount,
                usePersistedBaseChunkForBlocks);
        ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SPARSE_DELTA_APPLIED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "sparse delta replay: blocks=" + visitor.appliedBlocksCount() + ", blockEntities="
                        + visitor.restoredBlockEntitiesCount(),
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(chunkPos),
                null,
                operationId,
                runtimeDelta != null && runtimeDelta.isDirty(),
                null);
        if (ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO)) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.PROTO_CHUNK_SECTIONS_AFTER_DELTA,
                    ChunkTraceSeverity.INFO,
                    restoreEmptyResult ? ChunkTraceReason.RESTORE_EMPTY_RESULT : ChunkTraceReason.NONE,
                    RESTORE_SOURCE,
                    "server chunk after sparse replay: " + ChunkSectionDebugUtil.summarize(chunk),
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    runtimeDelta != null && runtimeDelta.isDirty(),
                    null);
        }
        ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.RESTORE_COMPLETED,
                ChunkTraceSeverity.INFO,
                restoreEmptyResult ? ChunkTraceReason.RESTORE_EMPTY_RESULT : ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "restore completed: blocks=" + visitor.appliedBlocksCount() + ", blockEntities="
                        + visitor.restoredBlockEntitiesCount() + ", blockReplay=" + visitor.blockApplyFailureCounters()
                        .describe() + (ServerHotpathMetrics.ENABLED ? ", sectionCursor="
                                                                      + visitor.sectionWriteCursorStats()
                                                                        .describe() : ""),
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(chunkPos),
                null,
                operationId,
                runtimeDelta != null && runtimeDelta.isDirty(),
                null);

        if (ChunkTraceInvariants.shouldAssertNonEmptyRestore(protoDelta, appliedCount)) {
            ChunkTraceStore.trace(ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.RESTORE_EMPTY_RESULT,
                    RESTORE_SOURCE,
                    "restore replay payload produced zero applied results: blocks=" + protoDelta.getBlockChangesCount()
                            + ", blockEntities=" + protoDelta.getBlockEntities()
                            .size(),
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    runtimeDelta != null && runtimeDelta.isDirty(),
                    null);
            if (ChunkisDebugConfig.allows(ChunkisDebugDomain.ASSERTIONS, ChunkTraceSeverity.ERROR)) {
                ChunkTraceStore.trace(ChunkisDebugDomain.ASSERTIONS,
                        ChunkTraceEventType.ASSERTION_FAILED,
                        ChunkTraceSeverity.ERROR,
                        ChunkTraceReason.RESTORE_EMPTY_RESULT,
                        RESTORE_SOURCE,
                        "restore zero-result diagnostics: payload=" + describeReplayPayload(protoDelta)
                                + ", blockReplay=" + visitor.blockApplyFailureCounters()
                                .describe(),
                        world.getRegistryKey()
                                .getValue()
                                .toString(),
                        DebugChunkKeys.of(chunkPos),
                        null,
                        operationId,
                        runtimeDelta != null && runtimeDelta.isDirty(),
                        null);
            }
        }
        if (ServerHotpathMetrics.ENABLED && (ServerHotpathMetrics.restoreCount() & 0x7F) == 0) {
            ServerHotpathMetrics.logRestoreSummary();
        }
    }

    /**
     * Replays pending entities from runtime delta parameters.
     *
     * @param world        target server world
     * @param chunk        target world chunk
     * @param runtimeDelta target runtime delta being populated
     * @param operationId  optional execution/operation trace ID label, may be null
     */
    public static void replayPendingEntitiesIfNeeded(final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            @org.jetbrains.annotations.Nullable final String operationId) {
        EntityReplayCoordinator.replayPendingEntitiesIfNeeded(world, chunk, runtimeDelta, operationId);
    }

    /**
     * Replays a single pending entity from parameters.
     *
     * @param world             target server world
     * @param chunk             target world chunk
     * @param runtimeDelta      target runtime delta being populated
     * @param operationId       optional execution/operation trace ID label, may be null
     * @param entityUuid        UUID string of target entity
     * @param fallbackEntityNbt fallback entity NBT data, may be null
     * @return ReplayResult outcome status
     */
    public static ReplayResult replayPendingEntityIfNeeded(final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            @org.jetbrains.annotations.Nullable final String operationId,
            final String entityUuid,
            @org.jetbrains.annotations.Nullable final NbtCompound fallbackEntityNbt) {
        return EntityReplayCoordinator.replayPendingEntityIfNeeded(world,
                chunk,
                runtimeDelta,
                operationId,
                entityUuid,
                fallbackEntityNbt);
    }

    /**
     * Applies one block state directly to a chunk section.
     *
     * <p>This avoids the normal high-level block mutation path because restoration
     * is replaying already validated persisted data. Section and height bounds are
     * checked before the write so corrupt delta entries cannot explode the entire
     * restore pass.</p>
     *
     * @param chunk         chunk to mutate
     * @param chunkPosition chunk position, used for logging
     * @param localX        local chunk X coordinate
     * @param localY        absolute world Y coordinate
     * @param localZ        local chunk Z coordinate
     * @param state         state to write
     * @param worldPosition absolute world position, used for stale block entity cleanup/logging
     * @param clearedToAir  whether restore pre-cleared the chunk to air before replay
     * @param counters      failure tracking counters
     * @param operationId   optional execution/operation trace ID label, may be null
     * @return {@code true} if the block was applied
     */
    static boolean applyBlockChange(final WorldChunk chunk,
            final ChunkPos chunkPosition,
            final int localX,
            final int localY,
            final int localZ,
            final BlockState state,
            final BlockPos worldPosition,
            @org.jetbrains.annotations.Nullable final BlockState previousState,
            final ChunkSection[] sections,
            final int bottomY,
            final int topYInclusive,
            final boolean tracePayloadWatches,
            final boolean clearedToAir,
            final ChunkRestoreBlockOperations.SectionWriteCursor sectionWriteCursor,
            final BlockApplyFailureCounters counters,
            @org.jetbrains.annotations.Nullable final String operationId) {
        return ChunkRestoreBlockOperations.applyBlockChange(chunk,
                chunkPosition,
                localX,
                localY,
                localZ,
                state,
                worldPosition,
                previousState,
                sections,
                bottomY,
                topYInclusive,
                tracePayloadWatches,
                clearedToAir,
                sectionWriteCursor,
                counters,
                operationId);
    }

    /**
     * Creates human-readable diagnostic descriptions mapping replayed delta data.
     *
     * @param delta source block delta
     * @return description summary text string
     */
    static String describeReplayPayload(final ChunkDelta<?, NbtCompound> delta) {
        if (delta == null) {
            return "sections=[], blockChanges=[], blockEntities=[]";
        }

        final TreeSet<Integer> sections = new TreeSet<>();
        final List<String> blockChanges = new ArrayList<>();
        final List<String> blockEntities = new ArrayList<>();

        delta.forEachBlock((x, y, z, state) -> {
            sections.add(y >> 4);
            blockChanges.add("(" + x + "," + y + "," + z + ")=" + state);
        });

        delta.getBlockEntities()
                .forEach((packedPos, nbt) -> {
                    final int x = io.liparakis.chunkis.core.BlockInstruction.unpackX(packedPos);
                    final int y = io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos);
                    final int z = io.liparakis.chunkis.core.BlockInstruction.unpackZ(packedPos);
                    sections.add(y >> 4);
                    final String id = nbt == null ? "null" : nbt.getString(ID_KEY)
                                                             .orElse("<missing-id>");
                    blockEntities.add("(" + x + "," + y + "," + z + ")=" + id);
                });

        blockEntities.sort(String::compareTo);

        return "sections=" + joinIntegers(sections) + ", blockChanges=" + blockChanges + ", blockEntities="
                + blockEntities;
    }

    /**
     * Preserves migrated authoritative block-entity payloads in the runtime delta
     * even when vanilla has not materialized live block entities yet.
     */
    static void seedMigratedAuthoritativeBlockEntities(final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta) {
        if (protoDelta == null || runtimeDelta == null
                || !CisNbtUtil.isMigratedAuthoritativeChunk(protoDelta.getChunkMetadata())
                || protoDelta.getBlockEntities()
                .isEmpty()) {
            return;
        }

        copyBlockEntityPayloads(protoDelta, runtimeDelta);
    }

    /**
     * Copies serialized block-entity payloads from one delta into another without
     * marking the destination dirty.
     */
    public static void copyBlockEntityPayloads(final ChunkDelta<BlockState, NbtCompound> sourceDelta,
            final ChunkDelta<BlockState, NbtCompound> targetDelta) {
        if (sourceDelta == null || targetDelta == null || sourceDelta.getBlockEntities()
                .isEmpty()) {
            return;
        }

        sourceDelta.getBlockEntities()
                .forEach((packedPos, nbt) -> {
                    if (nbt == null) {
                        return;
                    }
                    targetDelta.addBlockEntityData(io.liparakis.chunkis.core.BlockInstruction.unpackX(packedPos),
                            io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos),
                            io.liparakis.chunkis.core.BlockInstruction.unpackZ(packedPos),
                            nbt,
                            false);
                });
    }

    /**
     * Maps touched section flags to concrete section Y coordinates.
     *
     * @param bottomY         chunk bottom Y coordinate
     * @param touchedSections touched-section flags indexed from bottom to top
     * @return ordered section Y coordinates for touched sections only
     */
    static List<Integer> collectTouchedSectionYCoordinates(final int bottomY, final boolean[] touchedSections) {
        final List<Integer> sectionYs = new ArrayList<>();
        final int bottomSectionY = ChunkSectionPos.getSectionCoord(bottomY);
        for (int sectionIndex = 0; sectionIndex < touchedSections.length; sectionIndex++) {
            if (!touchedSections[sectionIndex]) {
                continue;
            }
            sectionYs.add(bottomSectionY + sectionIndex);
        }
        return sectionYs;
    }

    /**
     * Collects section Y coordinates that need derived-state refresh.
     *
     * @param bottomY           chunk bottom Y coordinate
     * @param touchedSections   touched-section flags indexed from bottom to top
     * @param refreshWholeChunk whether the restore replaced the chunk baseline
     * @return ordered section Y coordinates to refresh
     */
    static List<Integer> collectSectionYCoordinatesNeedingRefresh(final int bottomY,
            final boolean[] touchedSections,
            final boolean refreshWholeChunk) {
        if (!refreshWholeChunk) {
            return collectTouchedSectionYCoordinates(bottomY, touchedSections);
        }

        final List<Integer> sectionYs = new ArrayList<>(touchedSections.length);
        final int bottomSectionY = ChunkSectionPos.getSectionCoord(bottomY);
        for (int sectionIndex = 0; sectionIndex < touchedSections.length; sectionIndex++) {
            sectionYs.add(bottomSectionY + sectionIndex);
        }
        return sectionYs;
    }

    /**
     * Returns whether restore-time derived-state refresh should use the bulk path.
     *
     * <p>Large explicit deltas behave like full chunk replacement for lighting purposes.
     * Feeding every restored block through {@code checkBlock()} is too expensive in that case.</p>
     *
     * @param blockChangesCount   explicit block changes in the restored delta
     * @param touchedSectionCount number of touched sections
     * @param refreshWholeChunk   whether the restore already requires whole-chunk refresh
     * @return true when bulk refresh is cheaper and appropriate
     */
    static boolean shouldUseBulkRefresh(final int blockChangesCount,
            final int touchedSectionCount,
            final int touchedColumnCount,
            final boolean refreshWholeChunk) {
        return refreshWholeChunk || (touchedSectionCount > 0
                && touchedColumnCount >= BULK_REFRESH_TOUCHED_COLUMNS_THRESHOLD
                && blockChangesCount >= touchedSectionCount * BULK_REFRESH_BLOCKS_PER_SECTION_THRESHOLD);
    }

    /**
     * Copies every section's block container so the restore's light changes can be found afterwards.
     *
     * @param chunk chunk about to be restored
     * @return per-section copies; {@code null} where the section is missing
     */
    @SuppressWarnings("unchecked")
    static PalettedContainer<BlockState>[] copySectionBlocks(final WorldChunk chunk) {
        final ChunkSection[] sections = chunk.getSectionArray();
        final PalettedContainer<BlockState>[] copies = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) {
            copies[i] = sections[i] == null ? null : sections[i].getBlockStateContainer().copy();
        }
        return copies;
    }

    /**
     * Pulls light in from the neighbours of a chunk that vanilla lit from scratch, once it is fully loaded.
     *
     * <p>Vanilla's light pass for a chunk only pushes its own light outwards; light spilling in from a
     * neighbour that was lit earlier is normally restored from the saved light data, which Chunkis does not
     * store. Re-checking the chunk's transparent border cells makes the engine re-propagate that light.</p>
     *
     * @param world    world
     * @param chunkPos chunk being loaded
     */
    private static void pullNeighbourLightOnceLoaded(final ServerWorld world, final ChunkPos chunkPos) {
        world.getServer().execute(() -> {
            final ChunkHolder holder = world.getChunkManager().chunkLoadingManager
                    .getCurrentChunkHolder(chunkPos.toLong());
            if (holder == null) {
                return;
            }
            holder.getAccessibleFuture().thenAcceptAsync(loaded -> loaded.ifPresent(chunk -> {
                final ServerLightingProvider lightingProvider = world.getChunkManager().getLightingProvider();
                ((BatchedLightChecks) lightingProvider).chunkis$checkBlocks(chunkPos.x, chunkPos.z,
                        transparentBorderCells(chunk));
            }), world.getServer());
        });
    }

    /**
     * Lists the cells on a chunk's four vertical faces that light can pass through.
     *
     * @param chunk loaded chunk
     * @return packed {@code BlockPos} longs
     */
    private static LongArrayList transparentBorderCells(final WorldChunk chunk) {
        final LongArrayList cells = new LongArrayList();
        final ChunkSection[] sections = chunk.getSectionArray();
        final int startX = chunk.getPos().getStartX();
        final int startZ = chunk.getPos().getStartZ();
        for (int i = 0; i < sections.length; i++) {
            final int baseY = chunk.getBottomY() + (i << 4);
            for (int y = 0; y < 16; y++) {
                for (int a = 0; a < 16; a++) {
                    addIfTransparent(cells, sections[i], startX, baseY, startZ, 0, y, a);
                    addIfTransparent(cells, sections[i], startX, baseY, startZ, 15, y, a);
                    if (a > 0 && a < 15) {
                        addIfTransparent(cells, sections[i], startX, baseY, startZ, a, y, 0);
                        addIfTransparent(cells, sections[i], startX, baseY, startZ, a, y, 15);
                    }
                }
            }
        }
        return cells;
    }

    /**
     * Adds a section-local cell if light can pass through its block.
     *
     * @param cells   output list
     * @param section section holding the cell
     * @param startX  chunk start X
     * @param baseY   section bottom Y
     * @param startZ  chunk start Z
     * @param x       local X
     * @param y       local Y
     * @param z       local Z
     */
    private static void addIfTransparent(final LongArrayList cells, final ChunkSection section, final int startX,
            final int baseY, final int startZ, final int x, final int y, final int z) {
        if (section.getBlockState(x, y, z).getOpacity() < MAX_OPACITY) {
            cells.add(BlockPos.asLong(startX + x, baseY + y, startZ + z));
        }
    }

    /**
     * Queues a light check at every position where the restore changed a block in a way that affects
     * light, exactly as {@code setBlockState} would have. The light engine then lowers and raises light
     * around those positions, neighbours included.
     *
     * @param lightingProvider world lighting provider
     * @param chunk            restored chunk
     * @param sections         live chunk sections
     * @param blocksBefore     section block copies taken before the restore
     */
    static void checkLightChanges(final ServerLightingProvider lightingProvider, final WorldChunk chunk,
            final ChunkSection[] sections, final PalettedContainer<BlockState>[] blocksBefore) {
        final BlockState air = Blocks.AIR.getDefaultState();
        final int startX = chunk.getPos().getStartX();
        final int startZ = chunk.getPos().getStartZ();
        final LongArrayList changed = new LongArrayList();
        for (int i = 0; i < sections.length; i++) {
            final PalettedContainer<BlockState> beforeBlocks = i < blocksBefore.length ? blocksBefore[i] : null;
            // A side holding only air needs no per-block reads: every state on it is air.
            final PalettedContainer<BlockState> before = beforeBlocks != null
                    && beforeBlocks.hasAny(state -> !state.isAir()) ? beforeBlocks : null;
            final PalettedContainer<BlockState> after = sections[i] != null && !sections[i].isEmpty()
                    ? sections[i].getBlockStateContainer()
                    : null;
            if (before == null && after == null) {
                continue;
            }
            final int baseY = chunk.getBottomY() + (i << 4);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockState oldState = before == null ? air : before.get(x, y, z);
                        final BlockState newState = after == null ? air : after.get(x, y, z);
                        if (oldState != newState && ChunkLightProvider.needsLightUpdate(oldState, newState)) {
                            changed.add(BlockPos.asLong(startX + x, baseY + y, startZ + z));
                        }
                    }
                }
            }
        }
        ((BatchedLightChecks) lightingProvider).chunkis$checkBlocks(chunk.getPos().x, chunk.getPos().z, changed);
    }

    /**
     * Finishes derived state for a chunk whose sections already held the restored blocks when vanilla converted
     * it: vanilla computed its heightmaps from those sections then, and its light steps, still ahead, refresh
     * the sky-light surface and use the stored light (see {@link CisNbtUtil#loadsWithStoredLight}). Only an
     * unlit chunk needs Chunkis: vanilla's from-scratch light pass does not pull light in from lit neighbours.
     *
     * @param world world
     * @param chunk restored chunk
     */
    static void refreshPreloadedChunkState(final ServerWorld world, final WorldChunk chunk) {
        if (!chunk.isLightOn()) {
            pullNeighbourLightOnceLoaded(world, chunk.getPos());
        }
    }

    /**
     * Rebuilds server-side derived chunk data after raw restore writes.
     *
     * <p>Restore bypasses normal block update hooks, so heightmaps and the light engine
     * must be nudged manually to converge on the restored block grid.</p>
     *
     * @param world             target server world
     * @param chunk             restored chunk
     * @param sourceDelta       restored payload containing explicit block edits
     * @param sections          live chunk sections
     * @param blocksBefore      copies of the section block containers taken before the restore wrote
     *                          anything; {@code null} entries were missing sections
     * @param bottomY           chunk bottom Y
     * @param touchedSections   touched-section flags
     * @param refreshWholeChunk whether the restore replaced the chunk baseline
     */
    static void refreshDerivedChunkState(final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> sourceDelta,
            final ChunkSection[] sections,
            final PalettedContainer<BlockState>[] blocksBefore,
            final int bottomY,
            final boolean[] touchedSections,
            final int touchedColumnCount,
            final boolean refreshWholeChunk) {
        final List<Integer> sectionYs = collectSectionYCoordinatesNeedingRefresh(bottomY,
                touchedSections,
                refreshWholeChunk);
        if (sectionYs.isEmpty()) {
            return;
        }
        final boolean useBulkRefresh = shouldUseBulkRefresh(sourceDelta.getBlockChangesCount(),
                sectionYs.size(),
                touchedColumnCount,
                refreshWholeChunk);

        if (useBulkRefresh) {
            Heightmap.populateHeightmaps(chunk, ChunkStatus.NORMAL_HEIGHTMAP_TYPES);
        } else {
            for (final Heightmap.Type type : ChunkStatus.NORMAL_HEIGHTMAP_TYPES) {
                final Heightmap heightmap = chunk.getHeightmap(type);
                sourceDelta.forEachBlock(heightmap::trackUpdate);
            }
        }
        chunk.refreshSurfaceY();
        // A chunk loaded unlit (see ChunkLoadNbtBuilder) is restored before vanilla's light steps run, which
        // light it from its restored blocks; touching the light engine before them corrupts that pass.
        if (!chunk.isLightOn()) {
            pullNeighbourLightOnceLoaded(world, chunk.getPos());
            return;
        }
        // Loaded with the light stored alongside these blocks, so it already describes them.
        if (CisNbtUtil.loadsWithStoredLight(sourceDelta.getChunkMetadata())) {
            return;
        }

        final ServerLightingProvider lightingProvider = world.getChunkManager()
                .getLightingProvider();
        lightingProvider.setColumnEnabled(chunk.getPos(), true);

        final int bottomSectionY = ChunkSectionPos.getSectionCoord(bottomY);
        for (final int sectionY : sectionYs) {
            final ChunkSectionPos sectionPos = ChunkSectionPos.from(chunk.getPos(), sectionY);
            final int sectionIndex = sectionY - bottomSectionY;
            final ChunkSection section =
                    sectionIndex >= 0 && sectionIndex < sections.length ? sections[sectionIndex] : null;
            lightingProvider.setSectionStatus(sectionPos, section == null || section.isEmpty());
        }

        final BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        final int chunkStartX = chunk.getPos()
                .getStartX();
        final int chunkStartZ = chunk.getPos()
                .getStartZ();
        // Raw restore writes bypass setBlockState's light checks, so the light still describes the blocks
        // from before the restore (vanilla lit the terrain it generated). Queue the checks vanilla would.
        checkLightChanges(lightingProvider, chunk, sections, blocksBefore);
        if (!useBulkRefresh) {
            sourceDelta.forEachBlock((x, y, z, state) -> {
                mutablePos.set(chunkStartX + x, y, chunkStartZ + z);
                world.getChunkManager()
                        .markForUpdate(mutablePos);
            });
        }
        lightingProvider.propagateLight(chunk.getPos());
        for (final int sectionY : sectionYs) {
            final ChunkSectionPos sectionPos = ChunkSectionPos.from(chunk.getPos(), sectionY);
            world.getChunkManager()
                    .onLightUpdate(net.minecraft.world.LightType.BLOCK, sectionPos);
            world.getChunkManager()
                    .onLightUpdate(net.minecraft.world.LightType.SKY, sectionPos);
        }
        if (useBulkRefresh) {
            resendFullChunkToWatchingPlayers(world, chunk, lightingProvider);
        }
    }

    /**
     * Reports whether {@code chunk} is the world's accessible chunk at its position, i.e. vanilla may
     * already have sent it to players. A chunk still being promoted from proto is not.
     *
     * <p>Never blocks: a blocking chunk lookup here would wait on the promotion that is running.</p>
     *
     * @param world owning world
     * @param chunk restored chunk
     * @return true when players may already hold this chunk
     */
    private static boolean isPublished(final ServerWorld world, final WorldChunk chunk) {
        return LoadedChunks.getIfAccessible(world, chunk.getPos().x, chunk.getPos().z) == chunk;
    }

    /**
     * Resends one full chunk packet to players already watching the chunk.
     *
     * <p>Base-backed restore rewrites the effective chunk baseline. A full resend is
     * cheaper than thousands of per-block update packets.</p>
     *
     * @param world            target server world
     * @param chunk            restored chunk
     * @param lightingProvider active lighting provider
     */
    private static void resendFullChunkToWatchingPlayers(final ServerWorld world,
            final WorldChunk chunk,
            final ServerLightingProvider lightingProvider) {
        final List<ServerPlayerEntity> players = world.getChunkManager().chunkLoadingManager.getPlayersWatchingChunk(
                chunk.getPos(),
                false);
        if (players.isEmpty()) {
            return;
        }
        if (!isPublished(world, chunk)) {
            // Still being promoted from proto: vanilla sends it to these players once it becomes accessible.
            return;
        }

        final ChunkDataS2CPacket chunkPacket = new ChunkDataS2CPacket(chunk, lightingProvider, null, null);
        for (final ServerPlayerEntity player : players) {
            if (player.isRemoved() || player.isDisconnected()) {
                continue;
            }
            player.networkHandler.sendPacket(chunkPacket);
        }
    }

    /**
     * Helper mapping sets of integers to formatted arrays text.
     *
     * @param values set of target integers
     * @return formatted array string
     */
    private static String joinIntegers(final Set<Integer> values) {
        final StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (final int value : values) {
            if (!first) {
                builder.append(',');
            }
            builder.append(value);
            first = false;
        }
        return builder.append(']')
                .toString();
    }

    /**
     * Replay outcomes representing individual entity spawn results.
     */
    public enum ReplayStatus {
        /**
         * Replayed entity spawned and verified.
         */
        SPAWNED,

        /**
         * Replayed entity was already present in the target chunk.
         */
        ALREADY_PRESENT,

        /**
         * Chunk is not loaded.
         */
        CHUNK_NOT_READY,

        /**
         * Persistent payload was missing or could not be found.
         */
        PAYLOAD_MISSING,

        /**
         * Entity was spawned but failed visibility checks (retried).
         */
        SPAWN_REJECTED_TRANSIENT,

        /**
         * Duplicate UUID UUID conflicts.
         */
        DUPLICATE_UUID_CONFLICT,

        /**
         * Unrecoverable failure.
         */
        PERMANENT_FAILURE
    }

    /**
     * Result wrapper representing replay outcomes.
     *
     * @param status status code
     * @param reason description text reason
     */
    public record ReplayResult(ReplayStatus status, String reason) {

    }

    /**
     * Specialized subclass mapping failure metrics loops.
     */
    static final class BlockApplyFailureCounters extends ChunkRestoreBlockOperations.FailureCounters {

        /**
         * Default constructor.
         */
        BlockApplyFailureCounters() {
            super();
        }
    }
}
