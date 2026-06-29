package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.EntityWatchTracker;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class EntityPayloadTracer {
    private EntityPayloadTracer() {
        throw new AssertionError("Utility class");
    }

    public static void traceCapturedEntities(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final List<NbtCompound> entities
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || entities.isEmpty()) {
            return;
        }

        for (final NbtCompound entityNbt : entities) {
            final String entityUuid = PayloadWatchSummaries.entityUuid(entityNbt);
            if (entityUuid == null) {
                continue;
            }

            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(PayloadWatchSummaries.worldId(world), entityUuid);
            if (target == null) {
                continue;
            }

            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "capture",
                    "PayloadWatchTracer#traceCapturedEntities",
                    "entity captured",
                    PayloadWatchSummaries.worldId(world),
                    chunkPos,
                    null,
                    target,
                    PayloadWatchSummaries.summarizeEntity(target, entityNbt),
                    null
            );
        }
    }

    public static void traceRestoredEntity(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final Entity entity,
            final NbtCompound nbt,
            final String operationId
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                PayloadWatchSummaries.worldId(world),
                entity.getUuidAsString()
        );
        if (target == null) {
            return;
        }
        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_RESTORE_APPLIED,
                "restore",
                "PayloadWatchTracer#traceRestoredEntity",
                "entity restored to live world",
                PayloadWatchSummaries.worldId(world),
                chunkPos,
                operationId,
                target,
                PayloadWatchSummaries.summarizeEntity(target, nbt),
                null
        );
    }

    public static void traceRestoreEntityInstructionVisited(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final NbtCompound nbt,
            final String operationId,
            final String source
    ) {
        PayloadWatchTracer.traceEntityRestoreStage(
                world,
                chunkPos,
                nbt,
                operationId,
                ChunkTraceEventType.WATCH_RESTORE_INSTRUCTION_VISITED,
                "restore-entity-visit",
                source,
                "decoded entity payload visited by restore",
                null
        );
    }

    public static void traceRestoreEntityApplyAttempt(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final NbtCompound nbt,
            final String operationId,
            final String source
    ) {
        PayloadWatchTracer.traceEntityRestoreStage(
                world,
                chunkPos,
                nbt,
                operationId,
                ChunkTraceEventType.WATCH_RESTORE_APPLY_ATTEMPT,
                "restore-entity-attempt",
                source,
                "attempting to materialize decoded entity payload into live server world",
                null
        );
    }

    public static void traceRestoreEntitySkipped(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final String entityUuid,
            final String operationId,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                PayloadWatchSummaries.worldId(world),
                entityUuid
        );
        if (target == null) {
            return;
        }
        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_SKIPPED,
                "restore",
                "PayloadWatchTracer#traceRestoreEntitySkipped",
                message,
                PayloadWatchSummaries.worldId(world),
                chunkPos,
                operationId,
                target,
                target.describe(),
                null
        );
    }

    public static void traceEntityPresenceAfterChunkFull(
            final ServerWorld world,
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(world);
        final ChunkPos chunkPos = chunk.getPos();
        PayloadWatchTracer.forEachWatchedEntityState(world, chunk, expectedDelta, operationId, match -> {
            final PayloadWatchTarget target = match.target();
            final String resolvedOperationId = match.operationId();
            final Entity liveEntity = match.liveEntity();
            final NbtCompound expectedNbt = match.expectedNbt();
            EntityWatchTracker.assertReloadedAfterUnload(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    liveEntity
            );
            PayloadWatchTracer.traceWatch(
                    liveEntity != null
                            ? ChunkTraceEventType.WATCH_PRESENT_AFTER_CHUNK_FULL
                            : ChunkTraceEventType.WATCH_FAILED,
                    "chunk-full-entity",
                    source,
                    liveEntity != null
                            ? "watched entity present after chunk became sendable"
                            : "watched entity missing after chunk became sendable",
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    PayloadWatchSummaries.summarizeExpectedEntityAndPresence(target, expectedNbt, liveEntity, chunk),
                    null
            );
        });
    }

    public static void traceEntityReplayState(
            final ServerWorld world,
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final ChunkTraceEventType eventType,
            final String stage,
            final String source,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || chunk == null) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(world);
        final ChunkPos chunkPos = chunk.getPos();
        PayloadWatchTracer.forEachWatchedEntityState(world, chunk, expectedDelta, operationId, match -> {
            final PayloadWatchTarget target = match.target();
            final String resolvedOperationId = match.operationId();
            final Entity liveEntity = match.liveEntity();
            final NbtCompound expectedNbt = match.expectedNbt();
            PayloadWatchTracer.traceWatch(
                    eventType,
                    stage,
                    source,
                    message,
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    PayloadWatchSummaries.summarizeExpectedEntityAndPresence(target, expectedNbt, liveEntity, chunk),
                    null
            );
        });
    }

    public static void traceLiveEntityRemoved(
            final ServerWorld world,
            final Entity entity,
            final Entity.RemovalReason reason,
            @Nullable final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || entity == null) {
            return;
        }

        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                PayloadWatchSummaries.worldId(world),
                entity.getUuidAsString()
        );
        if (target == null) {
            return;
        }

        final ChunkPos chunkPos = entity.getChunkPos();
        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_ENTITY_REMOVED,
                "live-remove",
                source,
                "watched entity removed from live server world",
                PayloadWatchSummaries.worldId(world),
                chunkPos,
                operationId,
                target,
                PayloadWatchSummaries.summarizeRemovedEntity(target, entity, reason, source),
                null
        );
    }

    public static void traceEntityChunkTransfer(
            final ServerWorld world,
            final Entity entity,
            @Nullable final String operationId,
            final String stage,
            final String source,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || entity == null) {
            return;
        }

        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                PayloadWatchSummaries.worldId(world),
                entity.getUuidAsString()
        );
        if (target == null) {
            return;
        }
        EntityWatchTracker.markTransfer(target, stage, entity.getChunkPos());

        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_CAPTURED,
                stage,
                source,
                message,
                PayloadWatchSummaries.worldId(world),
                entity.getChunkPos(),
                operationId,
                target,
                PayloadWatchSummaries.summarizeLiveEntity(target, entity, source),
                null
        );
    }

    public static void traceEntityTrackingEvent(
            final ServerWorld world,
            final Entity entity,
            final ServerPlayerEntity player,
            final String stage,
            final String source,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || entity == null || player == null) {
            return;
        }

        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                PayloadWatchSummaries.worldId(world),
                entity.getUuidAsString()
        );
        if (target == null) {
            return;
        }
        if ("entity-tracking-start".equals(stage)) {
            EntityWatchTracker.clearPendingReload(target);
        }
        EntityWatchTracker.rememberChunk(target, entity.getChunkPos());

        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_CAPTURED,
                stage,
                source,
                message,
                PayloadWatchSummaries.worldId(world),
                entity.getChunkPos(),
                null,
                target,
                PayloadWatchSummaries.summarizeLiveEntity(target, entity, source)
                        + " player=" + player.getName().getString()
                        + " playerId=" + player.getUuidAsString(),
                null
        );
    }

    public static void traceEntityChunkReentry(
            final ServerWorld world,
            final WorldChunk chunk,
            final String stage,
            final String source,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || chunk == null) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(world);
        final ChunkPos chunkPos = chunk.getPos();
        PayloadWatchTracer.forEachWatchedEntityState(world, chunk, null, null, match -> {
            final PayloadWatchTarget target = match.target();
            final Entity liveEntity = match.liveEntity();
            EntityWatchTracker.assertReloadedAfterUnload(worldId, chunkPos, target, null, liveEntity);
            PayloadWatchTracer.traceWatch(
                    liveEntity != null
                            ? ChunkTraceEventType.WATCH_CAPTURED
                            : ChunkTraceEventType.WATCH_FAILED,
                    stage,
                    source,
                    message,
                    worldId,
                    chunkPos,
                    null,
                    target,
                    liveEntity != null
                            ? PayloadWatchSummaries.summarizeLiveEntity(target, liveEntity, source)
                            : target.describe()
                                    + " chunkStatus=" + chunk.getStatus()
                                    + " chunkInstanceId=" + PayloadWatchSummaries.chunkInstanceId(chunk)
                                    + " source=" + source
                                    + " thread=" + Thread.currentThread().getName(),
                    null
            );
        });
    }
}
