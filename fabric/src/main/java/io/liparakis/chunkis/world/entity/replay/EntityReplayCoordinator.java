package io.liparakis.chunkis.world.entity.replay;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityQueries;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Coordinates replay of pending Chunkis entity payloads into a loaded
 * {@link ServerWorld}.
 *
 * <p>Entity payloads are stored in a {@link ChunkDelta} and replayed when the
 * target chunk becomes available. Replay may succeed immediately, be deferred
 * via {@link ScheduledEntityReplayQueue}, or fail permanently depending on world
 * state at the time of the attempt.</p>
 *
 * <p><b>Threading:</b> all methods must be called from the server thread.</p>
 */
public final class EntityReplayCoordinator {

    private static final String SOURCE_BULK = "EntityReplayCoordinator#replayPendingEntitiesIfNeeded";
    private static final String SOURCE_SINGLE = "EntityReplayCoordinator#replayPendingEntityIfNeeded";

    private EntityReplayCoordinator() {
        throw new AssertionError("Utility class");
    }

    /**
     * Replays all pending entity payloads from {@code runtimeDelta} into
     * {@code world}.
     *
     * <p>For each pending entity NBT, attempts to spawn the entity. Entities that
     * cannot be spawned immediately are forwarded to {@link ScheduledEntityReplayQueue}
     * for a later retry. If all entities are successfully replayed and the delta's
     * pending list is marked as legacy (suppressed initial repopulation), the pending
     * list is cleared.</p>
     *
     * <p><b>Note on {@code ALREADY_PRESENT} accounting:</b> entities found already in
     * the world are counted as failures so that {@code clearPendingEntities()} is not
     * called prematurely. They are scheduled for a retry tick which removes the pending
     * entry via {@link ScheduledEntityReplayQueue#tick}. This is intentional: it
     * ensures the pending list is cleaned up through the queue rather than by this
     * method directly. The trade-off is that the failure counter and trace message
     * will be non-zero even when every entity is actually present.</p>
     *
     * @param world        target server world; defensive null-check applied
     * @param chunk        target chunk; defensive null-check applied
     * @param runtimeDelta delta containing pending entity payloads; may be {@code null}
     * @param operationId  save-operation correlation ID for tracing; may be {@code null}
     */
    public static void replayPendingEntitiesIfNeeded(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            @Nullable final String operationId
    ) {
        if (world == null || chunk == null) {
            return;
        }
        if (runtimeDelta == null) {
            PayloadWatchTracer.traceEntityReplayState(
                    world,
                    chunk,
                    null,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "entity-replay-no-runtime-delta",
                    SOURCE_BULK,
                    "entity replay skipped: runtime delta missing"
            );
            return;
        }
        if (runtimeDelta.countPendingEntities() == 0) {
            PayloadWatchTracer.traceEntityReplayState(
                    world,
                    chunk,
                    runtimeDelta,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "entity-replay-no-pending-entities",
                    SOURCE_BULK,
                    "entity replay skipped: runtime delta has no pending entity payloads"
            );
            return;
        }

        final ChunkPos chunkPosition = chunk.getPos();
        final Box searchBox = ChunkEntityQueries.chunkColumnBox(world, chunkPosition);
        final int[] stats = {0, 0};

        PayloadWatchTracer.traceEntityReplayState(
                world,
                chunk,
                runtimeDelta,
                operationId,
                ChunkTraceEventType.WATCH_CAPTURED,
                "entity-replay-started",
                SOURCE_BULK,
                "entity replay started from runtime delta"
        );

        runtimeDelta.forEachPendingEntity(nbt -> {
            if (nbt == null) {
                return;
            }
            PayloadWatchTracer.traceRestoreEntityApplyAttempt(world, chunkPosition, nbt, operationId, SOURCE_BULK);
            EntityType.loadEntityWithPassengers(
                    nbt,
                    world,
                    SpawnReason.LOAD,
                    entity -> {
                        final EntityReplaySpawnResolver.SpawnOutcome outcome =
                                EntityReplaySpawnResolver.attempt(world, chunkPosition, searchBox, entity, nbt, operationId);
                        if (outcome.succeeded()) {
                            stats[0]++;
                            ScheduledEntityReplayQueue.acknowledge(entity.getUuidAsString());
                        } else {
                            stats[1]++;
                            if (outcome.shouldSchedule()) {
                                ScheduledEntityReplayQueue.schedule(world, chunkPosition, entity.getUuidAsString(), nbt);
                            }
                        }
                        return entity;
                    }
            );
        });

        if (stats[1] == 0) {
            finishFailureFreeBulkReplay(world, chunk, runtimeDelta, operationId);
            return;
        }

        PayloadWatchTracer.traceEntityReplayState(
                world,
                chunk,
                runtimeDelta,
                operationId,
                ChunkTraceEventType.WATCH_SKIPPED,
                "entity-replay-retained-pending",
                SOURCE_BULK,
                "entity replay retained pending payloads after failures=" + stats[1]
        );
    }

    /**
     * Replays a single pending entity payload, identified by {@code entityUuid},
     * from {@code runtimeDelta} or {@code fallbackEntityNbt}.
     *
     * <p>Used by {@link ScheduledEntityReplayQueue} when retrying a previously
     * deferred entity. The result status indicates whether the caller should remove
     * the entry from the queue, retry later, or give up permanently.</p>
     *
     * @param world             target server world; defensive null-check applied
     * @param chunk             target chunk; defensive null-check applied
     * @param runtimeDelta      delta containing pending entity payloads; may be {@code null}
     * @param operationId       save-operation correlation ID for tracing; may be {@code null}
     * @param entityUuid        UUID string of the entity to replay; defensive null/blank check applied
     * @param fallbackEntityNbt fallback NBT used when the entity is not found in
     *                          {@code runtimeDelta}; may be {@code null}
     * @return replay result describing outcome and whether to retry
     */
    public static ChunkRestorer.ReplayResult replayPendingEntityIfNeeded(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            @Nullable final String operationId,
            final String entityUuid,
            @Nullable final NbtCompound fallbackEntityNbt
    ) {
        if (world == null || chunk == null || entityUuid == null || entityUuid.isBlank()) {
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PERMANENT_FAILURE,
                    "missing world/chunk/entity uuid"
            );
        }
        if ((runtimeDelta == null || runtimeDelta.countPendingEntities() == 0) && fallbackEntityNbt == null) {
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world,
                    chunk.getPos(),
                    entityUuid,
                    operationId,
                    "entity replay skipped: runtime delta missing queued entity payloads"
            );
            EntityReplayDiagnostics.traceReplayDecision(
                    world,
                    chunk,
                    null,
                    entityUuid,
                    null,
                    false,
                    "payload-missing",
                    ChunkEntityQueries.chunkColumnBox(world, chunk.getPos())
            );
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PAYLOAD_MISSING,
                    "runtime delta missing queued entity payloads"
            );
        }

        NbtCompound entityNbt = runtimeDelta == null
                ? null
                : ChunkEntityQueries.findPendingEntityNbt(runtimeDelta, entityUuid);
        if (entityNbt == null) {
            entityNbt = fallbackEntityNbt;
        }
        if (entityNbt == null) {
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world,
                    chunk.getPos(),
                    entityUuid,
                    operationId,
                    "entity replay skipped: queued entity payload missing from runtime delta"
            );
            EntityReplayDiagnostics.traceReplayDecision(
                    world,
                    chunk,
                    runtimeDelta,
                    entityUuid,
                    null,
                    false,
                    "payload-missing-for-uuid",
                    ChunkEntityQueries.chunkColumnBox(world, chunk.getPos())
            );
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PAYLOAD_MISSING,
                    "queued entity payload missing from runtime delta"
            );
        }

        final UUID queuedUuid;
        try {
            queuedUuid = UUID.fromString(entityUuid);
        } catch (final IllegalArgumentException ignored) {
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world,
                    chunk.getPos(),
                    entityUuid,
                    operationId,
                    "entity replay skipped: queued entity UUID was invalid"
            );
            EntityReplayDiagnostics.traceReplayDecision(
                    world,
                    chunk,
                    runtimeDelta,
                    entityUuid,
                    entityNbt,
                    false,
                    "invalid-uuid",
                    ChunkEntityQueries.chunkColumnBox(world, chunk.getPos())
            );
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PERMANENT_FAILURE,
                    "queued entity UUID was invalid"
            );
        }

        final ChunkPos chunkPosition = chunk.getPos();
        if (world.getChunkManager().getWorldChunk(chunkPosition.x, chunkPosition.z, false) == null) {
            EntityReplayDiagnostics.traceReplayDecision(
                    world,
                    chunk,
                    runtimeDelta,
                    entityUuid,
                    entityNbt,
                    false,
                    "chunk-not-loaded",
                    ChunkEntityQueries.chunkColumnBox(world, chunkPosition)
            );
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.CHUNK_NOT_READY,
                    "target chunk is not loaded"
            );
        }

        PayloadWatchTracer.traceEntityReplayState(
                world,
                chunk,
                runtimeDelta,
                operationId,
                ChunkTraceEventType.WATCH_CAPTURED,
                "entity-replay-started",
                SOURCE_SINGLE,
                "entity replay started from runtime delta"
        );
        PayloadWatchTracer.traceRestoreEntityApplyAttempt(world, chunkPosition, entityNbt, operationId, SOURCE_SINGLE);

        final Box searchBox = ChunkEntityQueries.chunkColumnBox(world, chunkPosition);
        final NbtCompound replayNbt = entityNbt;
        final ChunkRestorer.ReplayStatus[] callbackStatus = {ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT};

        EntityType.loadEntityWithPassengers(
                replayNbt,
                world,
                SpawnReason.LOAD,
                entity -> {
                    final EntityReplaySpawnResolver.SpawnOutcome outcome =
                            EntityReplaySpawnResolver.attempt(world, chunkPosition, searchBox, entity, replayNbt, operationId);
                    callbackStatus[0] = outcome.status();
                    if (outcome.shouldSchedule()) {
                        ScheduledEntityReplayQueue.schedule(world, chunkPosition, entity.getUuidAsString(), replayNbt);
                    }
                    return entity;
                }
        );

        final Entity afterReplay = world.getEntity(queuedUuid);
        final boolean materialized = afterReplay != null
                && ChunkEntityQueries.isMatchingLiveEntity(
                afterReplay,
                EntityPayloadNbt.findEntityType(replayNbt).orElse(null),
                chunkPosition
        );
        final boolean visibleAfterReplay = ChunkEntityQueries.isVisibleFromWorldQuery(world, queuedUuid, searchBox);
        final boolean confirmed = materialized && visibleAfterReplay;

        PayloadWatchTracer.traceEntityReplayState(
                world,
                chunk,
                runtimeDelta,
                operationId,
                confirmed ? ChunkTraceEventType.WATCH_CAPTURED : ChunkTraceEventType.WATCH_SKIPPED,
                "entity-replay-retained-pending",
                SOURCE_SINGLE,
                confirmed
                        ? "entity replay resolved queued entity payload"
                        : callbackStatus[0] == ChunkRestorer.ReplayStatus.ALREADY_PRESENT
                        ? "entity replay callback resolved but queued UUID was still absent from live world"
                        : "entity replay retained queued entity payload after unresolved spawn"
        );
        EntityReplayDiagnostics.traceReplayDecision(
                world,
                chunk,
                runtimeDelta,
                entityUuid,
                replayNbt,
                confirmed,
                "after-spawn materialized=" + materialized + " visible=" + visibleAfterReplay,
                searchBox
        );

        if (confirmed) {
            return callbackStatus[0] == ChunkRestorer.ReplayStatus.ALREADY_PRESENT
                    ? new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.ALREADY_PRESENT,
                    "matching live entity already present"
            )
                    : new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.SPAWNED,
                    "spawned and visible from world query"
            );
        }

        return new ChunkRestorer.ReplayResult(
                callbackStatus[0] == ChunkRestorer.ReplayStatus.ALREADY_PRESENT
                        ? ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT
                        : callbackStatus[0],
                "entity was not visible after replay attempt"
        );
    }

    /**
     * Returns {@code true} if every pending entity in {@code runtimeDelta} is
     * currently visible from a spatial world query in the expected chunk.
     *
     * <p>Iteration cannot short-circuit once a failure is found because
     * {@code forEachPendingEntity} does not support early exit; instead, the callback
     * skips further processing after the first miss.</p>
     *
     * @param world         server world to query
     * @param chunkPosition expected chunk location for each entity
     * @param runtimeDelta  delta whose pending entities are checked
     * @return {@code true} if all pending entities are visible, or if there are none
     */
    public static boolean allPendingEntitiesVisible(
            final ServerWorld world,
            final ChunkPos chunkPosition,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta
    ) {
        if (runtimeDelta.countPendingEntities() == 0) {
            return true;
        }
        final Box searchBox = ChunkEntityQueries.chunkColumnBox(world, chunkPosition);
        final boolean[] allVisible = {true};
        runtimeDelta.forEachPendingEntity(nbt -> {
            if (!allVisible[0] || nbt == null) {
                return;
            }
            final UUID uuid = EntityPayloadNbt.findUuid(nbt).orElse(null);
            allVisible[0] = uuid != null && ChunkEntityQueries.isVisibleFromWorldQuery(world, uuid, searchBox);
        });
        return allVisible[0];
    }

    /**
     * Finalizes a failure-free bulk replay by clearing legacy pending payloads when
     * required and emitting the corresponding completion trace.
     *
     * @param world        replay target world
     * @param chunk        replay target chunk
     * @param runtimeDelta replayed runtime delta
     * @param operationId  save-operation correlation ID; may be {@code null}
     */
    private static void finishFailureFreeBulkReplay(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            @Nullable final String operationId
    ) {
        final boolean suppressRepopulation = runtimeDelta.shouldSuppressInitialRepopulation();
        if (suppressRepopulation) {
            runtimeDelta.clearPendingEntities();
        }
        PayloadWatchTracer.traceEntityReplayState(
                world,
                chunk,
                runtimeDelta,
                operationId,
                ChunkTraceEventType.WATCH_CAPTURED,
                "entity-replay-retained-pending",
                SOURCE_BULK,
                suppressRepopulation
                        ? "entity replay completed without failures; cleared legacy pending payloads"
                        : "entity replay completed without failures; retained durable entity payloads"
        );
    }
}
