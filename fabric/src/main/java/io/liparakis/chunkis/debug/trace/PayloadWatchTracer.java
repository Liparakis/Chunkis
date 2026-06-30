package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
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
import java.util.UUID;
import java.util.function.Consumer;
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
     * Returns whether payload watch tracing is currently enabled.
     *
     * @return true when at least one payload watch is active
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean hasPayloadWatches() {
        return ChunkTraceWatchpoints.hasPayloadWatches();
    }

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private PayloadWatchTracer() {
        throw new AssertionError("Utility class");
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
                traceWatch(eventType, stage, source, message, worldId, chunkKey, operationId, target,
                        PayloadWatchSummaries.summarizeBlock(target, state), byteSize);
            }
        });

        delta.getBlockEntities()
                .long2ObjectEntrySet()
                .forEach(entry -> {
                    final LocalBlockPosition localPos = unpackLocalBlockPosition(entry.getLongKey());
                    final int worldX = chunkStartX + localPos.x();
                    final int worldZ = chunkStartZ + localPos.z();
                    final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                            worldId,
                            worldX,
                            localPos.y(),
                            worldZ
                    );
                    if (target != null) {
                        traceWatch(eventType, stage, source, message, worldId, chunkKey, operationId, target,
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
                traceWatch(eventType, stage, source, message, worldId, chunkKey, operationId, target,
                        PayloadWatchSummaries.summarizeEntity(target, entityNbt), byteSize);
            }
        });

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(worldId, chunkKey)) {
            if (target.type() == PayloadWatchType.ENTITY || !target.hasBlockCoordinates()) {
                continue;
            }
            if (!contains(delta, target, chunkStartX, chunkStartZ, worldId)) {
                traceWatch(ChunkTraceEventType.WATCH_FAILED, stage, source, "missing during " + stage, worldId,
                        chunkKey, operationId, target, target.describe(), byteSize);
            }
        }
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
        final String worldId = PayloadWatchSummaries.worldId(world);
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        if (storageEntryPresent) {
            traceDeltaStage(worldId, chunkPos, delta, operationId, ChunkTraceEventType.WATCH_STORAGE_READ,
                    "storage-read", "PayloadWatchTracer#traceDecodeOutcome", "payload bytes read from storage",
                    null);
        }

        traceDeltaStage(worldId, chunkPos, delta, operationId, ChunkTraceEventType.WATCH_DECODED, "decode",
                "PayloadWatchTracer#traceDecodeOutcome", "payload decoded", null);

        registerDecodedWatchTargets(worldId, chunkPos, delta, operationId);

        if (!storageEntryPresent) {
            return;
        }

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(worldId,
                DebugChunkKeys.of(chunkPos))) {
            if (target.type() == PayloadWatchType.ENTITY || !target.hasBlockCoordinates()) {
                continue;
            }
            if (!contains(delta, target, chunkPos.getStartX(), chunkPos.getStartZ(), worldId)) {
                traceWatch(ChunkTraceEventType.WATCH_FAILED, "decode", "PayloadWatchTracer#traceDecodeOutcome",
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

    /**
     * Checks if the given chunk delta contains block or entity updates matching active watchpoint targets.
     *
     * @param delta       chunk delta representation
     * @param target      watchpoint filter target
     * @param chunkStartX chunk X block coordinate origin start
     * @param chunkStartZ chunk Z block coordinate origin start
     * @param worldId     dimension registry ID string
     * @return true if matching updates exist
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean contains(final ChunkDeltaView<BlockState, NbtCompound> delta, final PayloadWatchTarget target,
            final int chunkStartX, final int chunkStartZ, final String worldId) {
        final boolean[] found = {false};

        if (target.type() == PayloadWatchType.BLOCK) {
            delta.forEachBlock((x, y, z, state) -> {
                if (found[0]) {
                    return;
                }
                final int worldX = chunkStartX + x;
                final int worldZ = chunkStartZ + z;
                found[0] = target.matchesBlock(worldId, PayloadWatchType.BLOCK, worldX, y, worldZ);
            });
            return found[0];
        }

        if (target.type() == PayloadWatchType.BLOCK_ENTITY) {
            delta.getBlockEntities()
                    .long2ObjectEntrySet()
                    .forEach(entry -> {
                        if (found[0]) {
                            return;
                        }
                        final LocalBlockPosition localPos = unpackLocalBlockPosition(entry.getLongKey());
                        final int worldX = chunkStartX + localPos.x();
                        final int worldZ = chunkStartZ + localPos.z();
                        found[0] = target.matchesBlock(
                                worldId,
                                PayloadWatchType.BLOCK_ENTITY,
                                worldX,
                                localPos.y(),
                                worldZ
                        );
                    });
            return found[0];
        }

        delta.forEachEntity(entityNbt -> {
            if (found[0] || entityNbt == null) {
                return;
            }
            final String uuid = PayloadWatchSummaries.entityUuid(entityNbt);
            found[0] = uuid != null && target.matchesEntity(worldId, uuid);
        });
        return found[0];
    }

    /**
     * Finds entity NBT matching watched UUID string.
     *
     * @param delta      chunk delta representation
     * @param entityUuid target entity UUID
     * @return matching entity NBT compound if found, or null
     */
    @Nullable
    static NbtCompound findWatchedEntityNbt(final ChunkDeltaView<BlockState, NbtCompound> delta, final String entityUuid) {
        final NbtCompound[] found = {null};
        delta.forEachEntity(entityNbt -> {
            if (found[0] != null || entityNbt == null) {
                return;
            }
            final String uuid = PayloadWatchSummaries.entityUuid(entityNbt);
            if (entityUuid.equals(uuid)) {
                found[0] = entityNbt;
            }
        });
        return found[0];
    }

    /**
     * Finds the expected block state for a watched target inside delta representation.
     *
     * @param delta    chunk delta representation
     * @param target   watchpoint filter target
     * @param chunkPos coordinates of the chunk
     * @param worldId  dimension registry ID string
     * @return expected BlockState if found, or null
     */
    @Nullable
    static BlockState findWatchedBlockState(final ChunkDeltaView<BlockState, NbtCompound> delta,
            final PayloadWatchTarget target, final ChunkPos chunkPos,
            final String worldId) {
        final BlockState[] found = {null};
        delta.forEachBlock((x, y, z, state) -> {
            if (found[0] != null) {
                return;
            }
            final int worldX = chunkPos.getStartX() + x;
            final int worldZ = chunkPos.getStartZ() + z;
            if (target.matchesBlock(worldId, PayloadWatchType.BLOCK, worldX, y, worldZ)) {
                found[0] = state;
            }
        });
        return found[0];
    }

    /**
     * Iterates over watched block states for the given chunk delta.
     *
     * @param worldId  target world dimension registry ID string
     * @param chunkPos coordinates of the chunk
     * @param delta    chunk delta representation
     * @param consumer receiver callback for matches found
     */
    static void forEachWatchedBlockState(
            final String worldId,
            final ChunkPos chunkPos,
            final ChunkDeltaView<BlockState, NbtCompound> delta,
            final Consumer<WatchedBlockState> consumer
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                DebugChunkKeys.of(chunkPos)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            consumer.accept(new WatchedBlockState(target, expectedState, null));
        }
    }

    /**
     * Iterates over resolved block states resolving expected delta parameters.
     *
     * @param chunk         loaded world chunk instance
     * @param expectedDelta chunk delta representation containing expected states, may be null
     * @param operationId   active trace session operation ID, may be null
     * @param consumer      receiver callback for matches found
     */
    static void forEachResolvedWatchedBlockState(
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final Consumer<WatchedBlockState> consumer
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                DebugChunkKeys.of(chunkPos)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState resolvedExpectedState = resolveExpectedState(
                    chunk,
                    expectedDelta,
                    target,
                    chunkPos,
                    worldId
            );
            if (resolvedExpectedState == null) {
                continue;
            }
            consumer.accept(new WatchedBlockState(
                    target,
                    resolvedExpectedState,
                    resolveOperationId(worldId, chunkPos, target, operationId)
            ));
        }
    }

    /**
     * Iterates over watched entity states resolving expected delta NBT parameters.
     *
     * @param world         target world instance
     * @param chunk         loaded world chunk instance
     * @param expectedDelta chunk delta representation containing expected states, may be null
     * @param operationId   active trace session operation ID, may be null
     * @param consumer      receiver callback for matches found
     */
    static void forEachWatchedEntityState(
            final ServerWorld world,
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final Consumer<WatchedEntityState> consumer
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || chunk == null) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(world);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloads()) {
            if (target.type() != PayloadWatchType.ENTITY || !target.matchesWorld(worldId)) {
                continue;
            }
            if (!EntityWatchTracker.shouldTraceForChunk(worldId, chunkPos, target)) {
                continue;
            }

            final Entity liveEntity = resolveWatchedEntity(world, target.entityUuid());
            if (liveEntity != null && !liveEntity.getChunkPos()
                    .equals(chunkPos)) {
                continue;
            }
            consumer.accept(new WatchedEntityState(
                    target,
                    resolveOperationId(worldId, chunkPos, target, operationId),
                    liveEntity,
                    expectedDelta != null ? findWatchedEntityNbt(expectedDelta, target.entityUuid()) : null
            ));
        }
    }

    /**
     * Resolves matching block target if watchpoints configured.
     *
     * @param worldId target world dimension registry ID string
     * @param pos     block coordinate pos
     * @return matching watchpoint target, or null
     */
    static @Nullable PayloadWatchTarget watchedBlockTarget(final String worldId, final BlockPos pos) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return null;
        }
        return ChunkTraceWatchpoints.watchedBlock(worldId, pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Unpacks packed 64-bit coordinates into localized block positions.
     *
     * @param packedPos packed 64-bit coordinate pos
     * @return LocalBlockPosition record
     */
    static LocalBlockPosition unpackLocalBlockPosition(final long packedPos) {
        return new LocalBlockPosition(
                io.liparakis.chunkis.core.BlockInstruction.unpackX(packedPos),
                io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos),
                io.liparakis.chunkis.core.BlockInstruction.unpackZ(packedPos)
        );
    }

    /**
     * Submits a trace log entry under the watch filters mapped by ChunkPos.
     *
     * @param eventType   the event type code to dispatch
     * @param stage       text label indicating the lifecycle stage name
     * @param source      class/method trace source trigger label
     * @param message     description detail text
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param operationId active trace session operation ID
     * @param target      watchpoint filter target
     * @param summary     description detail row summary text
     * @param byteSize    estimated size of payload in bytes, may be null
     */
    @SuppressWarnings("SameParameterValue")
    static void traceWatch(final ChunkTraceEventType eventType, final String stage, final String source,
            final String message, final String worldId, final ChunkPos chunkPos,
            final String operationId, final PayloadWatchTarget target, final String summary,
            final Integer byteSize) {
        traceWatch(eventType, stage, source, message, worldId, DebugChunkKeys.of(chunkPos), operationId, target,
                summary, byteSize);
    }

    /**
     * Submits a trace log entry under the watch filters mapped by DebugChunkKey.
     *
     * @param eventType   the event type code to dispatch
     * @param stage       text label indicating the lifecycle stage name
     * @param source      class/method trace source trigger label
     * @param message     description detail text
     * @param worldId     target world dimension registry ID string
     * @param chunkKey    chunk coordinate key
     * @param operationId active trace session operation ID
     * @param target      watchpoint filter target
     * @param summary     description detail row summary text
     * @param byteSize    estimated size of payload in bytes, may be null
     */
    static void traceWatch(final ChunkTraceEventType eventType, final String stage, final String source,
            final String message, final String worldId, final DebugChunkKey chunkKey,
            final String operationId, final PayloadWatchTarget target, final String summary,
            final Integer byteSize) {
        ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE, eventType,
                eventType == ChunkTraceEventType.WATCH_FAILED ? ChunkTraceSeverity.ERROR
                        : ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE, source, message, worldId, chunkKey, null, operationId, null, byteSize, target,
                stage, summary);
    }

    /**
     * Registers decoded targets inside BlockWatchTraceTracker.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param delta       chunk delta representation
     * @param operationId active trace session operation ID
     */
    static void registerDecodedWatchTargets(final String worldId, final ChunkPos chunkPos,
            final ChunkDeltaView<BlockState, NbtCompound> delta, final String operationId) {
        if (operationId == null) {
            return;
        }
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(worldId,
                DebugChunkKeys.of(chunkPos))) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            BlockWatchTraceTracker.registerDecodedTarget(worldId, chunkPos, target, operationId);
            traceWatch(ChunkTraceEventType.WATCH_DECODED_DELTA_STATE, "decode-delta-state", "PayloadWatchTracer" +
                            "#registerDecodedWatchTargets", "decoded payload state recorded before restore", worldId,
                    chunkPos, operationId, target, PayloadWatchSummaries.summarizeExpectedAndActual(target,
                            expectedState,
                            null,
                            null,
                            "DECODED_DELTA_ONLY",
                            "PayloadWatchTracer"
                                    +
                                    "#registerDecodedWatchTargets",
                            Thread.currentThread()
                                    .getName(),
                            null), null);
            traceChunkIdentityAndStatus(worldId, chunkPos, target, operationId, "decode", "PayloadWatchTracer" +
                    "#registerDecodedWatchTargets", null);
        }
    }

    /**
     * Resolves the active trace session operation ID.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId raw operation ID parameter, may be null
     * @return resolved session ID
     */
    @Nullable
    static String resolveOperationId(final String worldId, final ChunkPos chunkPos, final PayloadWatchTarget target,
            @Nullable final String operationId) {
        if (operationId != null) {
            return operationId;
        }
        if (!target.hasBlockCoordinates()) {
            return null;
        }
        return BlockWatchTraceTracker.resolveOperationId(worldId, chunkPos, target);
    }

    /**
     * Marks block watch visibility indicator as seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     */
    static void markVisibilitySeen(final String worldId, final ChunkPos chunkPos, final PayloadWatchTarget target,
            @Nullable final String operationId) {
        if (operationId == null) {
            return;
        }
        BlockWatchTraceTracker.markVisibilitySeen(worldId, chunkPos, target, operationId);
    }

    /**
     * Marks block watch restore decision indicator as seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     */
    static void markRestoreDecisionSeen(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId) {
        if (operationId == null) {
            return;
        }
        BlockWatchTraceTracker.markRestoreDecisionSeen(worldId, chunkPos, target, operationId);
    }

    /**
     * Evaluates if block watch restore decision was seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @return true if restore decision registered
     */
    static boolean isRestoreDecisionSeen(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId) {
        if (operationId == null) {
            return false;
        }
        return BlockWatchTraceTracker.isRestoreDecisionSeen(worldId, chunkPos, target, operationId);
    }

    /**
     * Asserts that a block watch restore decision was seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @param source      class/method trace source trigger label
     */
    static void assertRestoreDecisionSeen(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final String source) {
        BlockWatchTraceTracker.assertRestoreDecisionSeen(worldId, chunkPos, target, operationId, source);
    }

    /**
     * Traces the chunk's instance identity hash code and status metadata.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @param stage       text label indicating the lifecycle stage name
     * @param source      class/method trace source trigger label
     * @param chunk       associated world chunk instance, may be null
     */
    static void traceChunkIdentityAndStatus(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final String stage, final String source, @Nullable final Chunk chunk) {
        final String chunkInstanceId = PayloadWatchSummaries.chunkInstanceId(chunk);
        final String chunkStatus = chunk != null ? String.valueOf(chunk.getStatus()) : "UNAVAILABLE";
        traceWatch(ChunkTraceEventType.WATCH_CHUNK_INSTANCE_ID, stage, source, "chunk instance identity observed",
                worldId, chunkPos, operationId, target, "chunkInstanceId=" + chunkInstanceId + " source=" + source +
                        " thread=" + Thread.currentThread()
                        .getName(), null);
        traceWatch(ChunkTraceEventType.WATCH_CHUNK_STATUS, stage, source, "chunk status observed", worldId, chunkPos,
                operationId, target,
                "chunkStatus=" + chunkStatus + " chunkInstanceId=" + chunkInstanceId + " source" + "=" + source + " "
                        +
                        "thread=" + Thread.currentThread()
                        .getName(), null);
    }

    /**
     * Traces a restore mutation lifecycle stage event.
     *
     * @param eventType     event type code to dispatch
     * @param stage         text label indicating the lifecycle stage name
     * @param chunk         loaded world chunk instance
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     * @param skipReason    custom descriptive skip reason text, may be null
     */
    static void traceRestoreMutationStage(final ChunkTraceEventType eventType, final String stage,
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, @Nullable final String skipReason) {
        traceRestoreSetBlockStage(eventType, stage, chunk, pos, previousState, expectedState, operationId, source,
                skipReason, "setNewBlockReturnValue=UNAVAILABLE_DIRECT_SECTION_WRITE");
    }

    /**
     * Traces restore set block returned values.
     *
     * @param eventType     event type code to dispatch
     * @param stage         text label indicating the lifecycle stage name
     * @param chunk         loaded world chunk instance
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     * @param skipReason    custom descriptive skip reason text, may be null
     */
    @SuppressWarnings("SameParameterValue")
    static void traceRestoreSetBlockReturned(final ChunkTraceEventType eventType, final String stage,
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, @Nullable final String skipReason) {
        traceRestoreSetBlockStage(eventType, stage, chunk, pos, previousState, expectedState, operationId, source,
                skipReason, "setBlockReturnValue=UNAVAILABLE_DIRECT_SECTION_WRITE");
    }

    /**
     * Internal implementation helper routing restore set block stages.
     *
     * @param eventType        event type code to dispatch
     * @param stage            text label indicating the lifecycle stage name
     * @param chunk            loaded world chunk instance
     * @param pos              block coordinate pos
     * @param previousState    block state prior to application, may be null
     * @param expectedState    target block state to write, may be null
     * @param operationId      active restore session operation ID
     * @param source           class/method trace source label
     * @param skipReason       custom descriptive skip reason text, may be null
     * @param returnValueLabel label description for output values
     */
    private static void traceRestoreSetBlockStage(final ChunkTraceEventType eventType, final String stage,
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, @Nullable final String skipReason,
            final String returnValueLabel) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final PayloadWatchTarget target = watchedBlockTarget(worldId, pos);
        if (target == null) {
            return;
        }
        markRestoreDecisionSeen(worldId, chunk.getPos(), target, operationId);
        traceChunkIdentityAndStatus(worldId, chunk.getPos(), target, operationId, stage, source, chunk);
        traceWatch(eventType, stage, source, skipReason != null ? skipReason : "restore mutation stage", worldId,
                chunk.getPos(), operationId, target,
                "pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ() + " " + "expectedState=" + expectedState +
                        " previousState=" + previousState + " newState=" + expectedState + ' ' + returnValueLabel +
                        " actualStateImmediatelyAfter=" + chunk.getBlockState(pos) + " chunkInstanceId="
                        + PayloadWatchSummaries.chunkInstanceId(chunk) + " chunkStatus=" + chunk.getStatus()
                        + " source=" + source + " thread=" + Thread.currentThread()
                        .getName() + (skipReason != null ?
                        " skipReason=" + skipReason : ""), null);
    }

    /**
     * Records the applied chunk instance representation details inside tracker.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @param chunk       associated world chunk instance
     */
    static void recordAppliedChunkInstance(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final WorldChunk chunk) {
        BlockWatchTraceTracker.recordAppliedChunkInstance(worldId, chunkPos, target, operationId,
                PayloadWatchSummaries.chunkInstanceId(chunk));
    }

    /**
     * Asserts that subsequent updates use the same applied chunk instance.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @param source      class/method trace source trigger label
     * @param chunk       associated world chunk instance
     */
    static void assertSameAppliedChunkInstance(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final String source, final WorldChunk chunk) {
        BlockWatchTraceTracker.assertSameAppliedChunkInstance(worldId, chunkPos, target, operationId, source,
                PayloadWatchSummaries.chunkInstanceId(chunk));
    }

    /**
     * Resolves the expected block state from either the explicit expected delta or the chunk's attached delta.
     *
     * @param chunk                 loaded world chunk instance
     * @param explicitExpectedDelta explicitly expected delta, if any, may be null
     * @param target                watchpoint filter target
     * @param chunkPos              coordinates of the chunk
     * @param worldId               target world dimension registry ID string
     * @return resolved expected block state, or null
     */
    @Nullable
    static BlockState resolveExpectedState(final WorldChunk chunk, @Nullable final ChunkDelta<BlockState,
                    NbtCompound> explicitExpectedDelta, final PayloadWatchTarget target, final ChunkPos chunkPos,
            final String worldId) {
        final ChunkDelta<BlockState, NbtCompound> expectedDelta = explicitExpectedDelta != null ?
                explicitExpectedDelta : attachedDelta(chunk);
        return expectedDelta != null ? findWatchedBlockState(expectedDelta, target, chunkPos, worldId) : null;
    }

    /**
     * Safely retrieves the attached delta from a WorldChunk via interface duck.
     *
     * @param chunk loaded world chunk instance
     * @return attached chunk delta state, or null
     */
    @SuppressWarnings("unchecked")
    @Nullable
    static ChunkDelta<BlockState, NbtCompound> attachedDelta(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck duck)) {
            return null;
        }
        return (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta();
    }

    /**
     * Classifies block state events by thread and environment authoritativeness.
     *
     * @param chunk loaded world chunk instance
     * @return event classification string
     */
    static String classifySetBlockStateEvent(final WorldChunk chunk) {
        if (!chunk.getWorld()
                .isClient()) {
            return "SERVER_AUTHORITATIVE_EVENT";
        }
        return Thread.currentThread()
                .getName()
                .contains("Render") ? "CLIENT_RENDER_LOCAL_VISUAL_EVENT" :
                "CLIENT_LOCAL_VISUAL_EVENT";
    }

    /**
     * Submits trace logs detailing active entity restore stage updates.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the chunk
     * @param nbt         source entity NBT
     * @param operationId active restore operation ID
     * @param eventType   event type code to dispatch
     * @param stage       lifecycle stage name
     * @param source      class/method trace source label
     * @param message     description detail text
     * @param suffix      optional custom details text to append, may be null
     */
    @SuppressWarnings("SameParameterValue")
    static void traceEntityRestoreStage(final ServerWorld world, final ChunkPos chunkPos, final NbtCompound nbt,
            final String operationId, final ChunkTraceEventType eventType,
            final String stage, final String source, final String message,
            @Nullable final String suffix) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String entityUuid = PayloadWatchSummaries.entityUuid(nbt);
        if (entityUuid == null) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(PayloadWatchSummaries.worldId(world),
                entityUuid);
        if (target == null) {
            return;
        }
        traceWatch(eventType, stage, source, message, PayloadWatchSummaries.worldId(world), chunkPos, operationId,
                target,
                PayloadWatchSummaries.summarizeEntity(target, nbt) + " chunkStatus=entity-restore" + " source"
                        + "=" + source + " thread=" + Thread.currentThread()
                        .getName() + (suffix != null ?
                        " " + suffix : ""), null);
    }

    /**
     * Resolves live entity instances inside world by UUID string.
     *
     * @param world      target world instance
     * @param entityUuid target entity UUID string
     * @return resolved entity instance if found, or null
     */
    @Nullable
    static Entity resolveWatchedEntity(final ServerWorld world, final String entityUuid) {
        try {
            return world.getEntity(UUID.fromString(entityUuid));
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * State container tracking block state match details.
     *
     * @param target        watchpoint target
     * @param expectedState expected state code
     * @param operationId   active session operation ID, may be null
     */
    record WatchedBlockState(
            PayloadWatchTarget target,
            BlockState expectedState,
            @Nullable String operationId
    ) {

    }

    /**
     * State container tracking entity state match details.
     *
     * @param target      watchpoint target
     * @param operationId active session operation ID, may be null
     * @param liveEntity  resolved live entity instance, may be null
     * @param expectedNbt expected entity NBT, may be null
     */
    record WatchedEntityState(
            PayloadWatchTarget target,
            @Nullable String operationId,
            @Nullable Entity liveEntity,
            @Nullable NbtCompound expectedNbt
    ) {

    }

    /**
     * Helper mapping relative coordinates of local block offsets.
     *
     * @param x local X coordinate offset
     * @param y local Y coordinate offset
     * @param z local Z coordinate offset
     */
    record LocalBlockPosition(int x, int y, int z) {

    }
}
