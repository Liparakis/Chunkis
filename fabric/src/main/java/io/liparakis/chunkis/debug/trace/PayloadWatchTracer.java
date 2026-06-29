package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.watch.BlockWatchTraceTracker;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.EntityWatchTracker;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchType;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PayloadWatchTracer {

    private PayloadWatchTracer() {
        throw new AssertionError("Utility class");
    }

    public static void traceBlockSetStateEntered(
            final WorldChunk chunk, final BlockPos pos,
            final BlockState previous, final BlockState next, final int flags,
            final String caller,
            final ChunkMutationTrackingScope.Cause passiveCause,
            final boolean mutationSuppressed, final boolean mutationAccepted,
            final boolean deltaCreated, final int blockChangesBefore,
            final int blockChangesAfter, final long mutationGeneration,
            @Nullable final String message) {
        BlockPayloadTracer.traceBlockSetStateEntered(
                chunk, pos, previous, next, flags, caller, passiveCause,
                mutationSuppressed, mutationAccepted, deltaCreated, blockChangesBefore, blockChangesAfter,
                mutationGeneration, message
        );
    }

    public static void traceCapturedBlocks(final WorldChunk chunk) {
        BlockPayloadTracer.traceCapturedBlocks(chunk);
    }

    public static void traceCapturedBlockEntity(
            final String worldId, final ChunkPos chunkPos, final BlockPos pos,
            @Nullable final BlockEntity blockEntity,
            @Nullable final NbtCompound nbt) {
        BlockEntityPayloadTracer.traceCapturedBlockEntity(worldId, chunkPos, pos, blockEntity, nbt);
    }

    public static void traceSkippedBlockEntityCapture(
            final ServerWorld world, final ChunkPos chunkPos,
            final BlockPos pos, final String message) {
        BlockEntityPayloadTracer.traceSkippedBlockEntityCapture(world, chunkPos, pos, message);
    }

    public static void traceCapturedEntities(
            final ServerWorld world, final ChunkPos chunkPos,
            final List<NbtCompound> entities) {
        EntityPayloadTracer.traceCapturedEntities(world, chunkPos, entities);
    }

    public static void traceDeltaStage(
            final String worldId, final ChunkPos chunkPos, final ChunkDelta<BlockState,
                    NbtCompound> delta, final String operationId, final ChunkTraceEventType eventType,
            final String stage,
            final String source, final String message, final Integer byteSize) {
        traceDeltaStageInternal(
                worldId, new DebugChunkKey(chunkPos.x, chunkPos.z), chunkPos.getStartX(),
                chunkPos.getStartZ(), delta, operationId, eventType, stage, source, message, byteSize
        );
    }

    public static void traceDeltaStage(
            final String worldId, final DebugChunkKey chunkKey, final int chunkStartX,
            final int chunkStartZ, final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId, final ChunkTraceEventType eventType,
            final String stage, final String source, final String message,
            final Integer byteSize) {
        traceDeltaStageInternal(
                worldId, chunkKey, chunkStartX, chunkStartZ, delta, operationId, eventType, stage,
                source, message, byteSize
        );
    }

    private static void traceDeltaStageInternal(
            final String worldId, final DebugChunkKey chunkKey,
            final int chunkStartX, final int chunkStartZ,
            final ChunkDelta<BlockState, NbtCompound> delta,
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
                traceWatch(
                        eventType, stage, source, message, worldId, chunkKey, operationId, target,
                        PayloadWatchSummaries.summarizeBlock(target, state), byteSize
                );
            }
        });

        delta.getBlockEntities().long2ObjectEntrySet().forEach(entry -> {
            final int x = io.liparakis.chunkis.core.BlockInstruction.unpackX(entry.getLongKey());
            final int y = io.liparakis.chunkis.core.BlockInstruction.unpackY(entry.getLongKey());
            final int z = io.liparakis.chunkis.core.BlockInstruction.unpackZ(entry.getLongKey());
            final int worldX = chunkStartX + x;
            final int worldZ = chunkStartZ + z;
            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(worldId, worldX, y, worldZ);
            if (target != null) {
                traceWatch(
                        eventType, stage, source, message, worldId, chunkKey, operationId, target,
                        PayloadWatchSummaries.summarizeBlockEntity(target, null, entry.getValue()), byteSize
                );
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
                traceWatch(
                        eventType, stage, source, message, worldId, chunkKey, operationId, target,
                        PayloadWatchSummaries.summarizeEntity(target, entityNbt), byteSize
                );
            }
        });

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(worldId, chunkKey)) {
            if (target.type() == PayloadWatchType.ENTITY || !target.hasBlockCoordinates()) {
                continue;
            }
            if (!contains(delta, target, chunkStartX, chunkStartZ, worldId)) {
                traceWatch(
                        ChunkTraceEventType.WATCH_FAILED, stage, source, "missing during " + stage, worldId,
                        chunkKey, operationId, target, target.describe(), byteSize
                );
            }
        }
    }

    public static void traceDecodeOutcome(
            final ServerWorld world, final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta, final String operationId,
            final boolean storageEntryPresent) {
        final String worldId = PayloadWatchSummaries.worldId(world);
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        if (storageEntryPresent) {
            traceDeltaStage(
                    worldId, chunkPos, delta, operationId, ChunkTraceEventType.WATCH_STORAGE_READ, "storage" +
                            "-read", "PayloadWatchTracer#traceDecodeOutcome", "payload bytes read from storage", null
            );
        }

        traceDeltaStage(
                worldId, chunkPos, delta, operationId, ChunkTraceEventType.WATCH_DECODED, "decode",
                "PayloadWatchTracer#traceDecodeOutcome", "payload decoded", null
        );

        registerDecodedWatchTargets(worldId, chunkPos, delta, operationId);

        if (!storageEntryPresent) {
            return;
        }

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() == PayloadWatchType.ENTITY || !target.hasBlockCoordinates()) {
                continue;
            }
            if (!contains(delta, target, chunkPos.getStartX(), chunkPos.getStartZ(), worldId)) {
                traceWatch(
                        ChunkTraceEventType.WATCH_FAILED, "decode", "PayloadWatchTracer#traceDecodeOutcome",
                        "missing after decode", worldId, chunkPos, operationId, target, target.describe(), null
                );
            }
        }
    }

    public static void traceRestoreStarted(
            final ServerWorld world, final ChunkPos chunkPos, final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta, final String operationId) {
        RestorePayloadTracer.traceRestoreStarted(world, chunkPos, chunk, delta, operationId);
    }

    public static void traceProtoDeltaAttached(
            final String worldId, final Chunk chunk, final ChunkDelta<BlockState,
                    NbtCompound> delta, final String operationId, final String source) {
        ChunkLifecyclePayloadTracer.traceProtoDeltaAttached(worldId, chunk, delta, operationId, source);
    }

    public static void traceProtoDeltaPresentBeforeConversion(
            final String worldId, final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId, final String source) {
        ChunkLifecyclePayloadTracer.traceProtoDeltaPresentBeforeConversion(worldId, chunk, delta, operationId, source);
    }

    public static void traceProtoDeltaPresentAfterConversion(
            final String worldId, final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId, final String source) {
        ChunkLifecyclePayloadTracer.traceProtoDeltaPresentAfterConversion(worldId, chunk, delta, operationId, source);
    }

    public static void traceWorldChunkDeltaAttached(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId, final String source) {
        ChunkLifecyclePayloadTracer.traceWorldChunkDeltaAttached(chunk, delta, operationId, source);
    }

    public static void traceWorldChunkDeltaMissing(
            final WorldChunk chunk, final String operationId,
            final String source) {
        ChunkLifecyclePayloadTracer.traceWorldChunkDeltaMissing(chunk, operationId, source);
    }

    public static void checkUnrestoredAssertions() {
        BlockWatchTraceTracker.checkUnrestoredAssertions();
    }

    public static void tickEntityReloadAssertions() {
        EntityWatchTracker.tickAssertions();
    }

    public static void traceWorldChunkConstructorConsumed(
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta, @Nullable final String operationId,
            final String source) {
        ChunkLifecyclePayloadTracer.traceWorldChunkConstructorConsumed(chunk, expectedDelta, operationId, source);
    }

    public static void traceRestoreInstructionVisited(
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId, final String source) {
        RestorePayloadTracer.traceRestoreInstructionVisited(
                chunk, pos, previousState, expectedState, operationId,
                source
        );
    }

    public static void traceRestoreApplyAttempt(
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source) {
        RestorePayloadTracer.traceRestoreApplyAttempt(chunk, pos, previousState, expectedState, operationId, source);
    }

    public static void traceRestoreSetBlockReturned(
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId, final String source) {
        RestorePayloadTracer.traceRestoreSetBlockReturned(
                chunk, pos, previousState, expectedState, operationId,
                source
        );
    }

    public static void traceRestoreStateAfterSetBlock(
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId, final String source) {
        RestorePayloadTracer.traceRestoreStateAfterSetBlock(
                chunk, pos, previousState, expectedState, operationId,
                source
        );
    }

    public static void traceRestoreApplyFailed(
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, final String reason) {
        RestorePayloadTracer.traceRestoreApplyFailed(
                chunk, pos, previousState, expectedState, operationId, source,
                reason
        );
    }

    public static void traceRestoreSkipped(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            final String operationId, final String source) {
        RestorePayloadTracer.traceRestoreSkipped(chunk, expectedDelta, operationId, source);
    }

    public static void traceRestoredBlock(
            final WorldChunk chunk, final BlockPos pos, final BlockState state,
            final String operationId) {
        RestorePayloadTracer.traceRestoredBlock(chunk, pos, state, operationId);
    }

    public static void traceRestoreBlockFailure(
            final ServerWorld world, final ChunkPos chunkPos, final BlockPos pos,
            final String operationId, final String message) {
        RestorePayloadTracer.traceRestoreBlockFailure(world, chunkPos, pos, operationId, message);
    }

    public static void traceRestoredBlockEntity(
            final ServerWorld world, final ChunkPos chunkPos, final BlockPos pos,
            final BlockEntity blockEntity, final NbtCompound nbt,
            final String operationId) {
        BlockEntityPayloadTracer.traceRestoredBlockEntity(world, chunkPos, pos, blockEntity, nbt, operationId);
    }

    public static void traceRestoreBlockEntitySkipped(
            final ServerWorld world, final ChunkPos chunkPos,
            final BlockPos pos, final String operationId,
            final String message) {
        BlockEntityPayloadTracer.traceRestoreBlockEntitySkipped(world, chunkPos, pos, operationId, message);
    }

    public static void traceRestoredEntity(
            final ServerWorld world, final ChunkPos chunkPos, final Entity entity,
            final NbtCompound nbt, final String operationId) {
        EntityPayloadTracer.traceRestoredEntity(world, chunkPos, entity, nbt, operationId);
    }

    public static void traceRestoreEntityInstructionVisited(
            final ServerWorld world, final ChunkPos chunkPos,
            final NbtCompound nbt, final String operationId,
            final String source) {
        EntityPayloadTracer.traceRestoreEntityInstructionVisited(world, chunkPos, nbt, operationId, source);
    }

    public static void traceRestoreEntityApplyAttempt(
            final ServerWorld world, final ChunkPos chunkPos,
            final NbtCompound nbt, final String operationId,
            final String source) {
        EntityPayloadTracer.traceRestoreEntityApplyAttempt(world, chunkPos, nbt, operationId, source);
    }

    public static void traceRestoreEntitySkipped(
            final ServerWorld world, final ChunkPos chunkPos,
            final String entityUuid, final String operationId,
            final String message) {
        EntityPayloadTracer.traceRestoreEntitySkipped(world, chunkPos, entityUuid, operationId, message);
    }

    public static void traceEntityPresenceAfterChunkFull(
            final ServerWorld world, final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta, @Nullable final String operationId,
            final String source) {
        EntityPayloadTracer.traceEntityPresenceAfterChunkFull(world, chunk, expectedDelta, operationId, source);
    }

    public static void traceEntityReplayState(
            final ServerWorld world, final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId, final ChunkTraceEventType eventType
            , final String stage, final String source, final String message) {
        EntityPayloadTracer.traceEntityReplayState(
                world, chunk, expectedDelta, operationId, eventType, stage, source
                , message
        );
    }

    public static void traceLiveEntityRemoved(
            final ServerWorld world, final Entity entity,
            final Entity.RemovalReason reason, @Nullable final String operationId,
            final String source) {
        EntityPayloadTracer.traceLiveEntityRemoved(world, entity, reason, operationId, source);
    }

    public static void traceEntityChunkTransfer(
            final ServerWorld world, final Entity entity,
            @Nullable final String operationId, final String stage,
            final String source, final String message) {
        EntityPayloadTracer.traceEntityChunkTransfer(world, entity, operationId, stage, source, message);
    }

    public static void traceEntityTrackingEvent(
            final ServerWorld world, final Entity entity,
            final ServerPlayerEntity player, final String stage,
            final String source, final String message) {
        EntityPayloadTracer.traceEntityTrackingEvent(world, entity, player, stage, source, message);
    }

    public static void traceEntityChunkReentry(
            final ServerWorld world, final WorldChunk chunk, final String stage,
            final String source, final String message) {
        EntityPayloadTracer.traceEntityChunkReentry(world, chunk, stage, source, message);
    }

    public static void traceLiveChunkState(
            final WorldChunk chunk, final ChunkTraceEventType presentEventType,
            final String stage, final String source,
            @Nullable final String operationId, @Nullable final ChunkDelta<BlockState,
                    NbtCompound> expectedDelta) {
        ChunkLifecyclePayloadTracer.traceLiveChunkState(
                chunk, presentEventType, stage, source, operationId,
                expectedDelta
        );
    }

    static boolean contains(
            final ChunkDelta<BlockState, NbtCompound> delta, final PayloadWatchTarget target,
            final int chunkStartX, final int chunkStartZ, final String worldId) {
        final boolean[] found = { false };

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
            delta.getBlockEntities().long2ObjectEntrySet().forEach(entry -> {
                if (found[0]) {
                    return;
                }
                final int x = io.liparakis.chunkis.core.BlockInstruction.unpackX(entry.getLongKey());
                final int y = io.liparakis.chunkis.core.BlockInstruction.unpackY(entry.getLongKey());
                final int z = io.liparakis.chunkis.core.BlockInstruction.unpackZ(entry.getLongKey());
                final int worldX = chunkStartX + x;
                final int worldZ = chunkStartZ + z;
                found[0] = target.matchesBlock(worldId, PayloadWatchType.BLOCK_ENTITY, worldX, y, worldZ);
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

    @Nullable
    static NbtCompound findWatchedEntityNbt(final ChunkDelta<BlockState, NbtCompound> delta, final String entityUuid) {
        final NbtCompound[] found = { null };
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

    @Nullable
    static BlockState findWatchedBlockState(
            final ChunkDelta<BlockState, NbtCompound> delta,
            final PayloadWatchTarget target, final ChunkPos chunkPos,
            final String worldId) {
        final BlockState[] found = { null };
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

    static void traceWatch(
            final ChunkTraceEventType eventType, final String stage, final String source,
            final String message, final String worldId, final ChunkPos chunkPos,
            final String operationId, final PayloadWatchTarget target, final String summary,
            final Integer byteSize) {
        traceWatch(
                eventType, stage, source, message, worldId, new DebugChunkKey(chunkPos.x, chunkPos.z), operationId
                , target, summary, byteSize
        );
    }

    static void traceWatch(
            final ChunkTraceEventType eventType, final String stage, final String source,
            final String message, final String worldId, final DebugChunkKey chunkKey,
            final String operationId, final PayloadWatchTarget target, final String summary,
            final Integer byteSize) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE, eventType,
                eventType == ChunkTraceEventType.WATCH_FAILED ? ChunkTraceSeverity.ERROR : ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE, source, message, worldId, chunkKey, null, operationId, null, byteSize, target,
                stage, summary
        );
    }

    static void registerDecodedWatchTargets(
            final String worldId, final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta, final String operationId) {
        if (operationId == null) {
            return;
        }
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            BlockWatchTraceTracker.registerDecodedTarget(worldId, chunkPos, target, operationId, expectedState);
            traceWatch(
                    ChunkTraceEventType.WATCH_DECODED_DELTA_STATE, "decode-delta-state", "PayloadWatchTracer" +
                            "#registerDecodedWatchTargets", "decoded payload state recorded before restore", worldId,
                    chunkPos, operationId, target, PayloadWatchSummaries.summarizeExpectedAndActual(
                            target,
                            expectedState, null, null, "DECODED_DELTA_ONLY", "PayloadWatchTracer" +
                                    "#registerDecodedWatchTargets", Thread.currentThread().getName(), null
                    ), null
            );
            traceChunkIdentityAndStatus(
                    worldId, chunkPos, target, operationId, "decode", "PayloadWatchTracer" +
                            "#registerDecodedWatchTargets", null
            );
        }
    }

    @Nullable
    static String resolveOperationId(
            final String worldId, final ChunkPos chunkPos, final PayloadWatchTarget target,
            @Nullable final String operationId) {
        if (operationId != null) {
            return operationId;
        }
        if (!target.hasBlockCoordinates()) {
            return null;
        }
        return BlockWatchTraceTracker.resolveOperationId(worldId, chunkPos, target);
    }

    static void markVisibilitySeen(
            final String worldId, final ChunkPos chunkPos, final PayloadWatchTarget target,
            @Nullable final String operationId) {
        if (operationId == null) {
            return;
        }
        BlockWatchTraceTracker.markVisibilitySeen(worldId, chunkPos, target, operationId);
    }

    static void markRestoreDecisionSeen(
            final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId) {
        if (operationId == null) {
            return;
        }
        BlockWatchTraceTracker.markRestoreDecisionSeen(worldId, chunkPos, target, operationId);
    }

    static boolean isRestoreDecisionSeen(
            final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId) {
        if (operationId == null) {
            return false;
        }
        return BlockWatchTraceTracker.isRestoreDecisionSeen(worldId, chunkPos, target, operationId);
    }

    static void assertRestoreDecisionSeen(
            final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final String source) {
        BlockWatchTraceTracker.assertRestoreDecisionSeen(worldId, chunkPos, target, operationId, source);
    }

    static void traceChunkIdentityAndStatus(
            final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final String stage, final String source, @Nullable final Chunk chunk) {
        final String chunkInstanceId = PayloadWatchSummaries.chunkInstanceId(chunk);
        final String chunkStatus = chunk != null ? String.valueOf(chunk.getStatus()) : "UNAVAILABLE";
        traceWatch(
                ChunkTraceEventType.WATCH_CHUNK_INSTANCE_ID, stage, source, "chunk instance identity observed",
                worldId, chunkPos, operationId, target, "chunkInstanceId=" + chunkInstanceId + " source=" + source +
                        " thread=" + Thread.currentThread().getName(), null
        );
        traceWatch(
                ChunkTraceEventType.WATCH_CHUNK_STATUS, stage, source, "chunk status observed", worldId, chunkPos,
                operationId, target, "chunkStatus=" + chunkStatus + " chunkInstanceId=" + chunkInstanceId + " source" +
                        "=" + source + " thread=" + Thread.currentThread().getName(), null
        );
    }

    static void traceRestoreMutationStage(
            final ChunkTraceEventType eventType, final String stage,
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, @Nullable final String skipReason) {
        traceRestoreSetBlockStage(
                eventType, stage, chunk, pos, previousState, expectedState, operationId, source,
                skipReason, "setNewBlockReturnValue=UNAVAILABLE_DIRECT_SECTION_WRITE"
        );
    }

    static void traceRestoreSetBlockReturned(
            final ChunkTraceEventType eventType, final String stage,
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, @Nullable final String skipReason) {
        traceRestoreSetBlockStage(
                eventType, stage, chunk, pos, previousState, expectedState, operationId, source,
                skipReason, "setBlockReturnValue=UNAVAILABLE_DIRECT_SECTION_WRITE"
        );
    }

    private static void traceRestoreSetBlockStage(
            final ChunkTraceEventType eventType, final String stage,
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, @Nullable final String skipReason,
            final String returnValueLabel) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(
                worldId, pos.getX(), pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        markRestoreDecisionSeen(worldId, chunk.getPos(), target, operationId);
        traceChunkIdentityAndStatus(worldId, chunk.getPos(), target, operationId, stage, source, chunk);
        traceWatch(
                eventType, stage, source, skipReason != null ? skipReason : "restore mutation stage", worldId,
                chunk.getPos(), operationId, target, "pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ() + " " +
                        "expectedState=" + String.valueOf(expectedState) + " previousState=" + String.valueOf(previousState) + " newState=" + String.valueOf(expectedState) + ' ' + returnValueLabel + " actualStateImmediatelyAfter=" + chunk.getBlockState(pos) + " chunkInstanceId=" + PayloadWatchSummaries.chunkInstanceId(chunk) + " chunkStatus=" + chunk.getStatus() + " source=" + source + " thread=" + Thread.currentThread().getName() + (skipReason != null ? " skipReason=" + skipReason : ""), null
        );
    }

    static void recordAppliedChunkInstance(
            final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final WorldChunk chunk) {
        BlockWatchTraceTracker.recordAppliedChunkInstance(
                worldId, chunkPos, target, operationId,
                PayloadWatchSummaries.chunkInstanceId(chunk)
        );
    }

    static void assertSameAppliedChunkInstance(
            final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final String source, final WorldChunk chunk) {
        BlockWatchTraceTracker.assertSameAppliedChunkInstance(
                worldId, chunkPos, target, operationId, source,
                PayloadWatchSummaries.chunkInstanceId(chunk)
        );
    }

    @Nullable
    static BlockState resolveExpectedState(
            final WorldChunk chunk, @Nullable final ChunkDelta<BlockState,
                    NbtCompound> explicitExpectedDelta, final PayloadWatchTarget target, final ChunkPos chunkPos,
            final String worldId) {
        final ChunkDelta<BlockState, NbtCompound> expectedDelta = explicitExpectedDelta != null ?
                explicitExpectedDelta : attachedDelta(chunk);
        return expectedDelta != null ? findWatchedBlockState(expectedDelta, target, chunkPos, worldId) : null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    static ChunkDelta<BlockState, NbtCompound> attachedDelta(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck duck)) {
            return null;
        }
        return (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta();
    }

    static String classifySetBlockStateEvent(final WorldChunk chunk) {
        if (!chunk.getWorld().isClient()) {
            return "SERVER_AUTHORITATIVE_EVENT";
        }
        return Thread.currentThread().getName().contains("Render") ? "CLIENT_RENDER_LOCAL_VISUAL_EVENT" :
                "CLIENT_LOCAL_VISUAL_EVENT";
    }

    static void traceEntityRestoreStage(
            final ServerWorld world, final ChunkPos chunkPos, final NbtCompound nbt,
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
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                PayloadWatchSummaries.worldId(world),
                entityUuid
        );
        if (target == null) {
            return;
        }
        traceWatch(
                eventType, stage, source, message, PayloadWatchSummaries.worldId(world), chunkPos, operationId,
                target, PayloadWatchSummaries.summarizeEntity(target, nbt) + " chunkStatus=entity-restore" + " source" +
                        "=" + source + " thread=" + Thread.currentThread().getName() + (suffix != null ?
                        " " + suffix : ""), null
        );
    }

    @Nullable
    static Entity resolveWatchedEntity(final ServerWorld world, final String entityUuid) {
        try {
            return world.getEntity(UUID.fromString(entityUuid));
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }
}
