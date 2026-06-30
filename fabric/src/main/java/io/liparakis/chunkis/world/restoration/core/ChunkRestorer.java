package io.liparakis.chunkis.world.restoration.core;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.trace.ChunkTraceInvariants;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.ChunkSectionDebugUtil;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.world.entity.replay.EntityReplayCoordinator;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
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
    public static void restore(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta
    ) {
        restore(
                world,
                chunk,
                protoDelta,
                runtimeDelta,
                null
        );
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
    public static void restore(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final String operationId
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(protoDelta, "protoDelta");
        if (runtimeDelta != null) {
            runtimeDelta.clearBlockPayloads(false);
            runtimeDelta.clearBlockEntityPayloads(false);
            runtimeDelta.clearActiveEntities();
            runtimeDelta.setEntities(List.of(), false);
            runtimeDelta.setChunkMetadata(protoDelta.getChunkMetadata(), false);
            runtimeDelta.setSuppressInitialRepopulation(
                    protoDelta.shouldSuppressInitialRepopulation()
            );
            runtimeDelta.copyBlockPaletteFrom(protoDelta);
            runtimeDelta.ensureBlockCapacity(protoDelta.getBlockChangesCount());
        }

        final ChunkRestorationVisitor visitor = new ChunkRestorationVisitor(
                world,
                chunk,
                protoDelta,
                runtimeDelta,
                operationId
        );
        final ChunkPos chunkPos = chunk.getPos();
        final boolean hasPersistedBaseChunk =
                CisNbtUtil.hasPersistedBaseChunkNbt(protoDelta.getChunkMetadata());
        final boolean usePersistedBaseChunkForBlocks =
                CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(protoDelta.getChunkMetadata());
        final boolean invalidBlockEntityOnlyPayloadWithoutBase =
                ChunkTraceInvariants.hasInvalidBlockEntityOnlyPayloadWithoutBase(
                        protoDelta,
                        hasPersistedBaseChunk
                );

        if (ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO)) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.RESTORE_TX_START,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    RESTORE_SOURCE,
                    "starting restore with decoded payload: " + describeReplayPayload(protoDelta)
                            + ", baseChunkNbt="
                            + (usePersistedBaseChunkForBlocks
                            ? "used"
                            : hasPersistedBaseChunk ? "metadata-only" : "missing"),
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null
            );
        }
        PayloadWatchTracer.traceRestoreStarted(world, chunkPos, chunk, protoDelta, operationId);
        if (usePersistedBaseChunkForBlocks) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
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
                    null
            );
        }
        if (invalidBlockEntityOnlyPayloadWithoutBase) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
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
                    null
            );
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
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
                    null
            );
            return;
        }

        try {
            if (!usePersistedBaseChunkForBlocks) {
                ChunkRestoreBlockOperations.clearChunkToAir(chunk);
            }
            visitor.cleanupReplayedEntities(protoDelta);
            // ponytail: keep restore traversal explicit so the hot block path skips generic delta dispatch.
            if (runtimeDelta != null) {
                protoDelta.forEachBlockInstruction(visitor);
            } else {
                protoDelta.forEachBlock(visitor::visitBlock);
            }
            protoDelta.getBlockEntities()
                    .forEach((packedPos, nbt) -> visitor.visitBlockEntity(
                            io.liparakis.chunkis.core.BlockInstruction.unpackX(packedPos),
                            io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos),
                            io.liparakis.chunkis.core.BlockInstruction.unpackZ(packedPos),
                            nbt
                    ));
            if (visitor.replayLegacyEntities()) {
                protoDelta.forEachEntity(visitor::visitEntity);
            }
            visitor.finishRestoration();
            replayPendingEntitiesIfNeeded(world, chunk, runtimeDelta, operationId);
            if (runtimeDelta != null
                    && runtimeDelta.shouldSuppressInitialRepopulation()
                    && EntityReplayCoordinator.allPendingEntitiesVisible(world, chunk.getPos(), runtimeDelta)) {
                runtimeDelta.clearPendingEntities();
            }
        } catch (final RuntimeException e) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
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
                    null
            );
            throw e;
        }

        final int appliedCount = visitor.appliedBlocksCount()
                + visitor.restoredBlockEntitiesCount();
        final boolean restoreEmptyResult = ChunkTraceInvariants.shouldReportRestoreEmptyResult(
                protoDelta,
                appliedCount,
                usePersistedBaseChunkForBlocks
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SPARSE_DELTA_APPLIED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "sparse delta replay: blocks=" + visitor.appliedBlocksCount()
                        + ", blockEntities=" + visitor.restoredBlockEntitiesCount(),
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(chunkPos),
                null,
                operationId,
                runtimeDelta != null && runtimeDelta.isDirty(),
                null
        );
        if (ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO)) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
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
                    null
            );
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.RESTORE_COMPLETED,
                ChunkTraceSeverity.INFO,
                restoreEmptyResult ? ChunkTraceReason.RESTORE_EMPTY_RESULT : ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "restore completed: blocks=" + visitor.appliedBlocksCount()
                        + ", blockEntities=" + visitor.restoredBlockEntitiesCount()
                        + ", blockReplay=" + visitor.blockApplyFailureCounters()
                        .describe(),
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(chunkPos),
                null,
                operationId,
                runtimeDelta != null && runtimeDelta.isDirty(),
                null
        );

        if (ChunkTraceInvariants.shouldAssertNonEmptyRestore(
                protoDelta,
                appliedCount
        )) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.RESTORE_EMPTY_RESULT,
                    RESTORE_SOURCE,
                    "restore replay payload produced zero applied results: blocks="
                            + protoDelta.getBlockChangesCount()
                            + ", blockEntities="
                            + protoDelta.getBlockEntities()
                            .size(),
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    runtimeDelta != null && runtimeDelta.isDirty(),
                    null
            );
            if (ChunkisDebugConfig.allows(ChunkisDebugDomain.ASSERTIONS, ChunkTraceSeverity.ERROR)) {
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.ASSERTIONS,
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
                        null
                );
            }
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
    public static void replayPendingEntitiesIfNeeded(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            @org.jetbrains.annotations.Nullable final String operationId
    ) {
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
    public static ReplayResult replayPendingEntityIfNeeded(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            @org.jetbrains.annotations.Nullable final String operationId,
            final String entityUuid,
            @org.jetbrains.annotations.Nullable final NbtCompound fallbackEntityNbt
    ) {
        return EntityReplayCoordinator.replayPendingEntityIfNeeded(
                world,
                chunk,
                runtimeDelta,
                operationId,
                entityUuid,
                fallbackEntityNbt
        );
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
    static boolean applyBlockChange(
            final WorldChunk chunk,
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
            final BlockApplyFailureCounters counters,
            @org.jetbrains.annotations.Nullable final String operationId
    ) {
        return ChunkRestoreBlockOperations.applyBlockChange(
                chunk,
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
                counters,
                operationId
        );
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
                    final String id = nbt == null
                            ? "null"
                            : nbt.getString(ID_KEY)
                              .orElse("<missing-id>");
                    blockEntities.add("(" + x + "," + y + "," + z + ")=" + id);
                });

        blockEntities.sort(String::compareTo);

        return "sections=" + joinIntegers(sections)
                + ", blockChanges=" + blockChanges
                + ", blockEntities=" + blockEntities;
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
