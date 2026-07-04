package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.EntityWatchTracker;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic payload tracer tracking server world entity capture, materialize, and chunk re-entry events.
 */
public final class EntityPayloadTracer {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private EntityPayloadTracer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Logs trace information when in-memory entities are captured during a chunk delta write.
     *
     * @param world    target world instance
     * @param chunkPos coordinates of the enclosing chunk
     * @param entities list of captured entity compounds
     */
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

            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(PayloadWatchSummaries.worldId(world),
                    entityUuid);
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

    /**
     * Logs trace information when an entity is materialized and restored to the live world.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param entity      materialized entity instance
     * @param nbt         source compound NBT
     * @param operationId active restore operation ID
     */
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

    /**
     * Logs trace information when an entity instruction has been visited during restore processing.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param nbt         source entity NBT
     * @param operationId active restore operation ID
     * @param source      class/method trace source label
     */
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

    /**
     * Logs trace information when attempting to apply/materialize a decoded entity into the world.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param nbt         source entity NBT
     * @param operationId active restore operation ID
     * @param source      class/method trace source label
     */
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

    /**
     * Logs trace information when restoring an entity payload was skipped.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param entityUuid  entity UUID string
     * @param operationId active restore operation ID
     * @param message     reason why restore was skipped
     */
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

    /**
     * Logs trace information verifying entity presence after the enclosing chunk becomes sendable.
     *
     * @param world         target world instance
     * @param chunk         target world chunk
     * @param expectedDelta expected delta payload model, may be null
     * @param operationId   active restore operation ID, may be null
     * @param source        class/method trace source label
     */
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

    /**
     * Logs trace information matching entity presence state against expected models.
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

    /**
     * Logs trace information when a tracked entity gets removed from the live world.
     *
     * @param world       target world instance
     * @param entity      removed entity instance
     * @param reason      removal reason code
     * @param operationId active restore operation ID, may be null
     * @param source      class/method trace source label
     */
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

    /**
     * Logs trace information when an entity transfers between world chunks.
     *
     * @param world       target world instance
     * @param entity      transferring entity instance
     * @param operationId active restore operation ID, may be null
     * @param stage       lifecycle stage name
     * @param source      class/method trace source label
     * @param message     description detail text
     */
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

    /**
     * Logs trace information when an entity starts or stops being tracked by players.
     *
     * @param world   target world instance
     * @param entity  tracked entity instance
     * @param player  associated server player entity
     * @param stage   lifecycle stage name
     * @param source  class/method trace source label
     * @param message description detail text
     */
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
                        + " player=" + player.getName()
                        .getString()
                        + " playerId=" + player.getUuidAsString(),
                null
        );
    }

    /**
     * Logs trace information verifying entity presence during chunk re-entry events.
     *
     * @param world   target world instance
     * @param chunk   target world chunk
     * @param stage   lifecycle stage name
     * @param source  class/method trace source label
     * @param message description detail text
     */
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
                              + " thread=" + Thread.currentThread()
                                             .getName(),
                    null
            );
        });
    }
}
