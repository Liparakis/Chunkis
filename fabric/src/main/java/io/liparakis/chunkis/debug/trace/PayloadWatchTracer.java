package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchType;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.debug.watch.BlockWatchTraceTracker;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.EntityWatchTracker;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import java.util.List;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Main dispatcher coordinates the collection and submission of diagnostic payload trace logs.
 *
 * <p>Operates as a router delegating block events, block entities, and entity tracking scopes
 * to dedicated sub-tracers, while exposing shared collection filters and format helpers.</p>
 */
public final class PayloadWatchTracer {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private PayloadWatchTracer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns whether payload watch tracing is currently enabled.
     *
     * @return true when at least one payload watch is active
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean hasPayloadWatches() {
        return ChunkTraceWatchpoints.hasPayloadWatches();
    }

    /**
     * Delegates trace output for a block mutation state set entered.
     *
     * @param chunk              target world chunk
     * @param pos                block coordinate pos
     * @param previous           previous block state
     * @param next               next target block state
     * @param flags              block update flags
     * @param caller             class/method identifier of the caller
     * @param passiveCause       associated scope cause classification
     * @param mutationSuppressed true if tracking/saving is suppressed
     * @param mutationAccepted   true if tracking logic registers the change
     * @param deltaCreated       true if a new delta state change is created
     * @param blockChangesBefore delta block changes count prior to set state
     * @param blockChangesAfter  delta block changes count post set state
     * @param mutationGeneration internal modification tracking sequence generation number
     * @param message            optional diagnostic detail override message, may be null
     */
    public static void traceBlockSetStateEntered(final WorldChunk chunk, final BlockPos pos,
            final BlockState previous, final BlockState next, final int flags,
            final String caller,
            final ChunkMutationTrackingScope.Cause passiveCause,
            final boolean mutationSuppressed, final boolean mutationAccepted,
            final boolean deltaCreated, final int blockChangesBefore,
            final int blockChangesAfter, final long mutationGeneration,
            @Nullable final String message) {
        // Called for every setBlockState: skip before the sub-tracer allocates a world-id string.
        if (!hasPayloadWatches()) {
            return;
        }
        BlockPayloadTracer.traceBlockSetStateEntered(chunk, pos, previous, next, flags, caller, passiveCause,
                mutationSuppressed, mutationAccepted, deltaCreated, blockChangesBefore, blockChangesAfter,
                mutationGeneration, message);
    }

    /**
     * Delegates trace output for scanning block capture matching inside loaded chunk.
     *
     * @param chunk target world chunk
     */
    public static void traceCapturedBlocks(final WorldChunk chunk) {
        BlockPayloadTracer.traceCapturedBlocks(chunk);
    }

    /**
     * Delegates trace output for a captured block entity delta state.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the enclosing chunk
     * @param pos         coordinates of the block entity
     * @param blockEntity block entity instance, may be null
     * @param nbt         the captured block entity NBT compound, may be null
     */
    public static void traceCapturedBlockEntity(final String worldId, final ChunkPos chunkPos, final BlockPos pos,
            @Nullable final BlockEntity blockEntity,
            @Nullable final NbtCompound nbt) {
        BlockEntityPayloadTracer.traceCapturedBlockEntity(worldId, chunkPos, pos, blockEntity, nbt);
    }

    /**
     * Delegates trace output for skipped block entity captures.
     *
     * @param world    target world instance
     * @param chunkPos coordinates of the enclosing chunk
     * @param pos      coordinates of the block entity
     * @param message  reason why capture was skipped
     */
    public static void traceSkippedBlockEntityCapture(final ServerWorld world, final ChunkPos chunkPos,
            final BlockPos pos, final String message) {
        BlockEntityPayloadTracer.traceSkippedBlockEntityCapture(world, chunkPos, pos, message);
    }

    /**
     * Delegates trace output for in-memory entity captures.
     *
     * @param world    target world instance
     * @param chunkPos coordinates of the enclosing chunk
     * @param entities list of captured entity compounds
     */
    public static void traceCapturedEntities(final ServerWorld world, final ChunkPos chunkPos,
            final List<NbtCompound> entities) {
        EntityPayloadTracer.traceCapturedEntities(world, chunkPos, entities);
    }

    /**
     * Routes trace output for delta stage transitions mapped by ChunkPos.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param delta       the target chunk delta state
     * @param operationId active trace session operation ID
     * @param eventType   event type code to dispatch
     * @param stage       text label indicating the lifecycle stage name
     * @param source      class/method trace source trigger label
     * @param message     description detail text
     * @param byteSize    estimated size of payload in bytes, may be null
     */
    public static void traceDeltaStage(final String worldId, final ChunkPos chunkPos, final ChunkDeltaView<BlockState,
                    NbtCompound> delta, final String operationId, final ChunkTraceEventType eventType, final String stage,
            final String source, final String message, final Integer byteSize) {
        traceDeltaStageInternal(worldId, DebugChunkKeys.of(chunkPos), chunkPos.getStartX(), chunkPos.getStartZ(),
                delta, operationId, eventType, stage, source, message, byteSize);
    }

    /**
     * Routes trace output for delta stage transitions mapped by DebugChunkKey.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkKey    chunk coordinate key
     * @param chunkStartX chunk X origin start block position
     * @param chunkStartZ chunk Z origin start block position
     * @param delta       the target chunk delta state
     * @param operationId active trace session operation ID
     * @param eventType   event type code to dispatch
     * @param stage       text label indicating the lifecycle stage name
     * @param source      class/method trace source trigger label
     * @param message     description detail text
     * @param byteSize    estimated size of payload in bytes, may be null
     */
    public static void traceDeltaStage(final String worldId, final DebugChunkKey chunkKey, final int chunkStartX,
            final int chunkStartZ, final ChunkDeltaView<BlockState, NbtCompound> delta,
            final String operationId, final ChunkTraceEventType eventType,
            final String stage, final String source, final String message,
            final Integer byteSize) {
        traceDeltaStageInternal(worldId, chunkKey, chunkStartX, chunkStartZ, delta, operationId, eventType, stage,
                source, message, byteSize);
    }

    /**
     * Internal implementation for tracing delta stage details across blocks, block entities, and entities.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkKey    chunk coordinate key
     * @param chunkStartX chunk X origin start block position
     * @param chunkStartZ chunk Z origin start block position
     * @param delta       the target chunk delta state
     * @param operationId active trace session operation ID
     * @param eventType   event type code to dispatch
     * @param stage       text label indicating the lifecycle stage name
     * @param source      class/method trace source trigger label
     * @param message     description detail text
     * @param byteSize    estimated size of payload in bytes, may be null
     */
    private static void traceDeltaStageInternal(final String worldId, final DebugChunkKey chunkKey,
            final int chunkStartX, final int chunkStartZ,
            final ChunkDeltaView<BlockState, NbtCompound> delta,
            final String operationId, final ChunkTraceEventType eventType,
            final String stage, final String source, final String message,
            final Integer byteSize) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        Objects.requireNonNull(delta, "delta");

        delta.forEachBlock((x, y, z, state) -> {
            final int worldX = chunkStartX + x;
            final int worldZ = chunkStartZ + z;
            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(worldId, worldX, y, worldZ);
            if (target != null) {
                WatchTraceEmitter.traceWatch(eventType, stage, source, message, worldId, chunkKey, operationId, target,
                        PayloadWatchSummaries.summarizeBlock(target, state), byteSize);
            }
        });

        delta.getBlockEntities()
                .long2ObjectEntrySet()
                .forEach(entry -> {
                    final WatchPayloadMatcher.LocalBlockPosition localPos = WatchPayloadMatcher.unpackLocalBlockPosition(entry.getLongKey());
                    final int worldX = chunkStartX + localPos.x();
                    final int worldZ = chunkStartZ + localPos.z();
                    final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                            worldId,
                            worldX,
                            localPos.y(),
                            worldZ
                    );
                    if (target != null) {
                        WatchTraceEmitter.traceWatch(eventType, stage, source, message, worldId, chunkKey, operationId, target,
                                PayloadWatchSummaries.summarizeBlockEntity(target, null, entry.getValue()), byteSize);
                    }
                });

        delta.forEachEntity(entityNbt -> {
            if (entityNbt == null) {
                return;
            }
            final String entityUuid = PayloadWatchSummaries.entityUuid(entityNbt);
            if (entityUuid == null) {
                return;
            }
            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(worldId, entityUuid);
            if (target != null) {
                WatchTraceEmitter.traceWatch(eventType, stage, source, message, worldId, chunkKey, operationId, target,
                        PayloadWatchSummaries.summarizeEntity(target, entityNbt), byteSize);
            }
        });

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(worldId, chunkKey)) {
            if (target.type() == PayloadWatchType.ENTITY || !target.hasBlockCoordinates()) {
                continue;
            }
            if (!WatchPayloadMatcher.contains(delta, target, chunkStartX, chunkStartZ, worldId)) {
                WatchTraceEmitter.traceWatch(ChunkTraceEventType.WATCH_FAILED, stage, source, "missing during " + stage, worldId,
                        chunkKey, operationId, target, target.describe(), byteSize);
            }
        }
    }

    /**
     * Registers watched payloads for a load served from the in-memory tracked delta, so the new chunk
     * instance gets its own load operation instead of inheriting the previous instance's one.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the chunk
     * @param delta       tracked delta selected for the load
     * @param operationId active load operation ID
     */
    public static void traceTrackedLoadSelected(final ServerWorld world, final ChunkPos chunkPos,
            final ChunkDeltaView<BlockState, NbtCompound> delta, final String operationId) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        WatchTraceEmitter.registerDecodedWatchTargets(PayloadWatchSummaries.worldId(world), chunkPos, delta, operationId);
    }

    /**
     * Submits trace outputs detailing outcomes from deserializing chunk data.
     *
     * @param world               target world instance
     * @param chunkPos            coordinates of the chunk
     * @param delta               the decoded chunk delta state
     * @param operationId         active trace session operation ID
     * @param storageEntryPresent true if storage bytes were successfully loaded
     */
    public static void traceDecodeOutcome(final ServerWorld world, final ChunkPos chunkPos,
            final ChunkDeltaView<BlockState, NbtCompound> delta, final String operationId,
            final boolean storageEntryPresent) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String worldId = PayloadWatchSummaries.worldId(world);

        if (storageEntryPresent) {
            traceDeltaStage(worldId, chunkPos, delta, operationId, ChunkTraceEventType.WATCH_STORAGE_READ,
                    "storage-read", "PayloadWatchTracer#traceDecodeOutcome", "payload bytes read from storage",
                    null);
        }

        traceDeltaStage(worldId, chunkPos, delta, operationId, ChunkTraceEventType.WATCH_DECODED, "decode",
                "PayloadWatchTracer#traceDecodeOutcome", "payload decoded", null);

        WatchTraceEmitter.registerDecodedWatchTargets(worldId, chunkPos, delta, operationId);

        if (!storageEntryPresent) {
            return;
        }

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(worldId,
                DebugChunkKeys.of(chunkPos))) {
            if (target.type() == PayloadWatchType.ENTITY || !target.hasBlockCoordinates()) {
                continue;
            }
            if (!WatchPayloadMatcher.contains(delta, target, chunkPos.getStartX(), chunkPos.getStartZ(), worldId)) {
                WatchTraceEmitter.traceWatch(ChunkTraceEventType.WATCH_FAILED, "decode", "PayloadWatchTracer#traceDecodeOutcome",
                        "missing after decode", worldId, chunkPos, operationId, target, target.describe(), null);
            }
        }
    }

    /**
     * Delegates trace output indicating a chunk restore operation has initialized.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the chunk
     * @param chunk       the loaded world chunk instance
     * @param delta       the restoring chunk delta state
     * @param operationId active restore session operation ID
     */
    public static void traceRestoreStarted(final ServerWorld world, final ChunkPos chunkPos, final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta, final String operationId) {
        RestorePayloadTracer.traceRestoreStarted(world, chunkPos, chunk, delta, operationId);
    }

    /**
     * Delegates trace output for attaching decoded delta state to proto chunks.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunk       the target proto chunk instance
     * @param delta       the attached chunk delta
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceProtoDeltaAttached(final String worldId, final Chunk chunk, final ChunkDelta<BlockState,
            NbtCompound> delta, final String operationId, final String source) {
        ChunkLifecyclePayloadTracer.traceProtoDeltaAttached(worldId, chunk, delta, operationId, source);
    }

    /**
     * Delegates trace output checking proto chunk delta state before conversion occurs.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunk       the target proto chunk instance
     * @param delta       the attached chunk delta
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceProtoDeltaPresentBeforeConversion(final String worldId, final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId, final String source) {
        ChunkLifecyclePayloadTracer.traceProtoDeltaPresentBeforeConversion(worldId, chunk, delta, operationId, source);
    }

    /**
     * Delegates trace output checking proto chunk delta state after conversion occurs.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunk       the target proto chunk instance
     * @param delta       the attached chunk delta
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceProtoDeltaPresentAfterConversion(final String worldId, final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId, final String source) {
        ChunkLifecyclePayloadTracer.traceProtoDeltaPresentAfterConversion(worldId, chunk, delta, operationId, source);
    }

    /**
     * Delegates trace output for attaching decoded delta state to WorldChunks.
     *
     * @param chunk       the WorldChunk instance
     * @param delta       the attached chunk delta
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceWorldChunkDeltaAttached(final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId, final String source) {
        ChunkLifecyclePayloadTracer.traceWorldChunkDeltaAttached(chunk, delta, operationId, source);
    }

    /**
     * Delegates trace output showing that a WorldChunk lacks an attached delta state.
     *
     * @param chunk       the WorldChunk instance
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceWorldChunkDeltaMissing(final WorldChunk chunk, final String operationId,
            final String source) {
        ChunkLifecyclePayloadTracer.traceWorldChunkDeltaMissing(chunk, operationId, source);
    }

    /**
     * Invokes safety checks evaluating block watches that failed to receive proper restore applications.
     */
    public static void checkUnrestoredAssertions() {
        BlockWatchTraceTracker.checkUnrestoredAssertions();
    }

    /**
     * Invokes safety ticks verifying loaded tracked entities and detecting missing ticks.
     */
    public static void tickEntityReloadAssertions() {
        EntityWatchTracker.tickAssertions();
    }

    /**
     * Delegates trace output for block delta consumption during WorldChunk construction.
     *
     * @param chunk         the WorldChunk instance
     * @param expectedDelta the expected delta state, if any, may be null
     * @param operationId   active trace session operation ID, if any, may be null
     * @param source        class/method trace source trigger label
     */
    public static void traceWorldChunkConstructorConsumed(final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final String source) {
        ChunkLifecyclePayloadTracer.traceWorldChunkConstructorConsumed(chunk, expectedDelta, operationId, source);
    }

    /**
     * Delegates trace output indicating a block position has been visited during restore phase.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreInstructionVisited(final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId, final String source) {
        if (!hasPayloadWatches()) {
            return;
        }
        RestorePayloadTracer.traceRestoreInstructionVisited(chunk, pos, previousState, expectedState, operationId,
                source);
    }

    /**
     * Delegates trace output showing that a block restoration write is being attempted.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreApplyAttempt(final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source) {
        if (!hasPayloadWatches()) {
            return;
        }
        RestorePayloadTracer.traceRestoreApplyAttempt(chunk, pos, previousState, expectedState, operationId, source);
    }

    /**
     * Delegates trace output logging values returned after restoring block states.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreSetBlockReturned(final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId, final String source) {
        if (!hasPayloadWatches()) {
            return;
        }
        RestorePayloadTracer.traceRestoreSetBlockReturned(chunk, pos, previousState, expectedState, operationId,
                source);
    }

    /**
     * Delegates trace output evaluating post-write block values.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreStateAfterSetBlock(final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId, final String source) {
        if (!hasPayloadWatches()) {
            return;
        }
        RestorePayloadTracer.traceRestoreStateAfterSetBlock(chunk, pos, previousState, expectedState, operationId,
                source);
    }

    /**
     * Delegates trace output for failed block restore writes.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     * @param reason        detailed failure classification text
     */
    public static void traceRestoreApplyFailed(final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, final String reason) {
        RestorePayloadTracer.traceRestoreApplyFailed(chunk, pos, previousState, expectedState, operationId, source,
                reason);
    }

    /**
     * Delegates trace output when block restoration is skipped due to matching live states.
     *
     * @param chunk         target world chunk
     * @param expectedDelta chunk delta container containing expected block updates
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreSkipped(final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            final String operationId, final String source) {
        RestorePayloadTracer.traceRestoreSkipped(chunk, expectedDelta, operationId, source);
    }

    /**
     * Delegates trace output confirming block state restoration was applied successfully.
     *
     * @param chunk       target world chunk
     * @param pos         block coordinate pos
     * @param state       applied block state
     * @param operationId active restore session operation ID
     */
    public static void traceRestoredBlock(final WorldChunk chunk, final BlockPos pos, final BlockState state,
            final String operationId) {
        if (!hasPayloadWatches()) {
            return;
        }
        RestorePayloadTracer.traceRestoredBlock(chunk, pos, state, operationId);
    }

    /**
     * Delegates trace output indicating block restore failed due to internal errors.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param pos         coordinates of the block entity
     * @param operationId active restore operation ID
     * @param message     description detail text
     */
    public static void traceRestoreBlockFailure(final ServerWorld world, final ChunkPos chunkPos, final BlockPos pos,
            final String operationId, final String message) {
        RestorePayloadTracer.traceRestoreBlockFailure(world, chunkPos, pos, operationId, message);
    }

    /**
     * Delegates trace output indicating block entity restoration occurred.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param pos         coordinates of the block entity
     * @param blockEntity restored block entity instance
     * @param nbt         source compound NBT
     * @param operationId active restore operation ID
     */
    public static void traceRestoredBlockEntity(final ServerWorld world, final ChunkPos chunkPos, final BlockPos pos,
            final BlockEntity blockEntity, final NbtCompound nbt,
            final String operationId) {
        BlockEntityPayloadTracer.traceRestoredBlockEntity(world, chunkPos, pos, blockEntity, nbt, operationId);
    }

    /**
     * Delegates trace output showing block entity restoration was skipped.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param pos         coordinates of the block entity
     * @param operationId active restore operation ID
     * @param message     reason why restore was skipped
     */
    public static void traceRestoreBlockEntitySkipped(final ServerWorld world, final ChunkPos chunkPos,
            final BlockPos pos, final String operationId,
            final String message) {
        BlockEntityPayloadTracer.traceRestoreBlockEntitySkipped(world, chunkPos, pos, operationId, message);
    }

    /**
     * Delegates trace output checking whether watched block entities are present
     * in an intermediate vanilla chunk-NBT root.
     */
    public static void traceBlockEntityPresenceInChunkNbt(
            final String worldId,
            final ChunkPos chunkPos,
            @Nullable final String operationId,
            @Nullable final NbtCompound chunkNbt,
            final String stage,
            final String source
    ) {
        BlockEntityPayloadTracer.traceBlockEntityPresenceInChunkNbt(
                worldId,
                chunkPos,
                operationId,
                chunkNbt,
                stage,
                source
        );
    }

    /**
     * Emits a watch-scoped informational event for every watched payload in a chunk.
     */
    public static void traceChunkWatchContext(
            final String worldId,
            final ChunkPos chunkPos,
            @Nullable final String operationId,
            final String stage,
            final String source,
            final String message
    ) {
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                DebugChunkKeys.of(chunkPos)
        )) {
            WatchTraceEmitter.traceWatch(
                    ChunkTraceEventType.WATCH_CAPTURED,
                    stage,
                    source,
                    message,
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    message,
                    null
            );
        }
    }

    /**
     * Delegates trace output showing entity restoration occurred.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param entity      materialized entity instance
     * @param nbt         source compound NBT
     * @param operationId active restore operation ID
     */
    public static void traceRestoredEntity(final ServerWorld world, final ChunkPos chunkPos, final Entity entity,
            final NbtCompound nbt, final String operationId) {
        EntityPayloadTracer.traceRestoredEntity(world, chunkPos, entity, nbt, operationId);
    }

    /**
     * Delegates trace output marking an entity restore instructions visited.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param nbt         source entity NBT
     * @param operationId active restore operation ID
     * @param source      class/method trace source label
     */
    public static void traceRestoreEntityInstructionVisited(final ServerWorld world, final ChunkPos chunkPos,
            final NbtCompound nbt, final String operationId,
            final String source) {
        EntityPayloadTracer.traceRestoreEntityInstructionVisited(world, chunkPos, nbt, operationId, source);
    }

    /**
     * Delegates trace output indicating an entity restoration application write attempt.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param nbt         source entity NBT
     * @param operationId active restore operation ID
     * @param source      class/method trace source label
     */
    public static void traceRestoreEntityApplyAttempt(final ServerWorld world, final ChunkPos chunkPos,
            final NbtCompound nbt, final String operationId,
            final String source) {
        EntityPayloadTracer.traceRestoreEntityApplyAttempt(world, chunkPos, nbt, operationId, source);
    }

    /**
     * Delegates trace output showing entity restoration was skipped.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param entityUuid  entity UUID string
     * @param operationId active restore operation ID
     * @param message     reason why restore was skipped
     */
    public static void traceRestoreEntitySkipped(final ServerWorld world, final ChunkPos chunkPos,
            final String entityUuid, final String operationId,
            final String message) {
        EntityPayloadTracer.traceRestoreEntitySkipped(world, chunkPos, entityUuid, operationId, message);
    }

    /**
     * Delegates trace output verifying entity presence after the enclosing chunk becomes sendable.
     *
     * @param world         target world instance
     * @param chunk         target world chunk
     * @param expectedDelta expected delta payload model, may be null
     * @param operationId   active restore operation ID, may be null
     * @param source        class/method trace source label
     */
    public static void traceEntityPresenceAfterChunkFull(final ServerWorld world,
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final String source) {
        EntityPayloadTracer.traceEntityPresenceAfterChunkFull(world, chunk, expectedDelta, operationId, source);
    }

    /**
     * Delegates trace output matching entity presence state against expected models.
     *
     * @param world         target world instance
     * @param chunk         target world chunk
     * @param expectedDelta expected delta payload model, may be null
     * @param operationId   active restore operation ID, may be null
     * @param eventType     event classification type
     * @param stage         lifecycle stage name
     * @param source        class/method trace source label
     * @param message       custom detail description message text
     */
    public static void traceEntityReplayState(final ServerWorld world, final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId, final ChunkTraceEventType eventType
            , final String stage, final String source, final String message) {
        EntityPayloadTracer.traceEntityReplayState(world, chunk, expectedDelta, operationId, eventType, stage, source
                , message);
    }

    /**
     * Delegates trace output when a tracked entity gets removed from the live world.
     *
     * @param world       target world instance
     * @param entity      removed entity instance
     * @param reason      removal reason code
     * @param operationId active restore operation ID, may be null
     * @param source      class/method trace source label
     */
    public static void traceLiveEntityRemoved(final ServerWorld world, final Entity entity,
            final Entity.RemovalReason reason, @Nullable final String operationId,
            final String source) {
        EntityPayloadTracer.traceLiveEntityRemoved(world, entity, reason, operationId, source);
    }

    /**
     * Delegates trace output when an entity transfers between world chunks.
     *
     * @param world       target world instance
     * @param entity      transferring entity instance
     * @param operationId active restore operation ID, may be null
     * @param stage       lifecycle stage name
     * @param source      class/method trace source label
     * @param message     description detail text
     */
    public static void traceEntityChunkTransfer(final ServerWorld world, final Entity entity,
            @Nullable final String operationId, final String stage,
            final String source, final String message) {
        EntityPayloadTracer.traceEntityChunkTransfer(world, entity, operationId, stage, source, message);
    }

    /**
     * Delegates trace output when an entity starts or stops being tracked by players.
     *
     * @param world   target world instance
     * @param entity  tracked entity instance
     * @param player  associated server player entity
     * @param stage   lifecycle stage name
     * @param source  class/method trace source label
     * @param message description detail text
     */
    public static void traceEntityTrackingEvent(final ServerWorld world, final Entity entity,
            final ServerPlayerEntity player, final String stage,
            final String source, final String message) {
        EntityPayloadTracer.traceEntityTrackingEvent(world, entity, player, stage, source, message);
    }

    /**
     * Delegates trace output verifying entity presence during chunk re-entry events.
     *
     * @param world   target world instance
     * @param chunk   target world chunk
     * @param stage   lifecycle stage name
     * @param source  class/method trace source label
     * @param message description detail text
     */
    public static void traceEntityChunkReentry(final ServerWorld world, final WorldChunk chunk, final String stage,
            final String source, final String message) {
        EntityPayloadTracer.traceEntityChunkReentry(world, chunk, stage, source, message);
    }

    /**
     * Delegates live chunk state validations for block matches.
     *
     * @param chunk            the WorldChunk instance
     * @param presentEventType event type to log when block matches
     * @param stage            text label indicating the lifecycle stage name
     * @param source           class/method trace source trigger label
     * @param operationId      active trace session operation ID, may be null
     * @param expectedDelta    the expected delta, may be null
     */
    public static void traceLiveChunkState(final WorldChunk chunk, final ChunkTraceEventType presentEventType,
            final String stage, final String source,
            @Nullable final String operationId, @Nullable final ChunkDelta<BlockState,
                    NbtCompound> expectedDelta) {
        ChunkLifecyclePayloadTracer.traceLiveChunkState(chunk, presentEventType, stage, source, operationId,
                expectedDelta);
    }

}
