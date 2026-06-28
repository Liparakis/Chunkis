package io.liparakis.chunkis.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates replay of pending Chunkis entity payloads into a loaded
 * {@link ServerWorld}.
 *
 * <p>Entity payloads are stored in a {@link ChunkDelta} and replayed when the
 * target chunk becomes available. Replay may succeed immediately, be deferred
 * via {@link ScheduledEntityReplayQueue}, or fail permanently depending on
 * world state at the time of the attempt.</p>
 *
 * <p><b>Threading:</b> all methods must be called from the server thread.</p>
 */
final class EntityReplayCoordinator {

    /**
     * NBT key holding the entity type identifier string.
     */
    private static final String ID_KEY = "id";
    /**
     * NBT key holding the entity UUID as a 4-element int array.
     */
    private static final String UUID_KEY = "UUID";

    private static final String SOURCE_BULK = "EntityReplayCoordinator#replayPendingEntitiesIfNeeded";
    private static final String SOURCE_SINGLE = "EntityReplayCoordinator#replayPendingEntityIfNeeded";

    private EntityReplayCoordinator() {
        throw new AssertionError("Utility class");
    }

    /**
     * Replays all pending entity payloads from {@code runtimeDelta} into
     * {@code world}.
     *
     * <p>For each pending entity NBT, attempts to spawn the entity. Entities
     * that cannot be spawned immediately are forwarded to
     * {@link ScheduledEntityReplayQueue} for a later retry. If all entities
     * are successfully replayed and the delta's pending list is marked as
     * legacy (suppressed initial repopulation), the pending list is cleared.</p>
     *
     * @param world        target server world; defensive null-check applied
     * @param chunk        target chunk; defensive null-check applied
     * @param runtimeDelta delta containing pending entity payloads; may be {@code null}
     * @param operationId  save-operation correlation ID for tracing; may be {@code null}
     */
    static void replayPendingEntitiesIfNeeded(
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
                    world, chunk, null, operationId,
                    ChunkTraceEventType.WATCH_SKIPPED, "entity-replay-no-runtime-delta",
                    SOURCE_BULK, "entity replay skipped: runtime delta missing"
            );
            return;
        }
        if (runtimeDelta.countPendingEntities() == 0) {
            PayloadWatchTracer.traceEntityReplayState(
                    world, chunk, runtimeDelta, operationId,
                    ChunkTraceEventType.WATCH_SKIPPED, "entity-replay-no-pending-entities",
                    SOURCE_BULK, "entity replay skipped: runtime delta has no pending entity payloads"
            );
            return;
        }

        final ChunkPos chunkPosition = chunk.getPos();
        PayloadWatchTracer.traceEntityReplayState(
                world, chunk, runtimeDelta, operationId,
                ChunkTraceEventType.WATCH_CAPTURED, "entity-replay-started",
                SOURCE_BULK, "entity replay started from runtime delta"
        );

        // [0] = successes, [1] = failures — counters shared into the lambda.
        final int[] stats = { 0, 0 };
        final Box searchBox = chunkEntitySearchBox(world, chunkPosition);

        runtimeDelta.forEachPendingEntity(nbt -> {
            if (nbt == null) {
                return;
            }
            PayloadWatchTracer.traceRestoreEntityApplyAttempt(
                    world, chunkPosition, nbt, operationId, SOURCE_BULK
            );
            EntityType.loadEntityWithPassengers(
                    nbt, world, SpawnReason.LOAD, entity -> {
                        final SpawnOutcome outcome = attemptEntitySpawn(
                                world, chunkPosition, searchBox, entity, nbt, operationId
                        );
                        if (outcome.succeeded()) {
                            stats[0]++;
                            ScheduledEntityReplayQueue.acknowledge(entity.getUuidAsString());
                        } else {
                            stats[1]++;
                            if (outcome.shouldSchedule()) {
                                ScheduledEntityReplayQueue.schedule(
                                        world, chunkPosition, entity.getUuidAsString(), nbt
                                );
                            }
                        }
                        return entity;
                    }
            );
        });

        final int failures = stats[1];
        if (failures == 0) {
            final boolean suppressRepopulation = runtimeDelta.shouldSuppressInitialRepopulation();
            if (suppressRepopulation) {
                runtimeDelta.clearPendingEntities();
            }
            PayloadWatchTracer.traceEntityReplayState(
                    world, chunk, runtimeDelta, operationId,
                    ChunkTraceEventType.WATCH_CAPTURED, "entity-replay-retained-pending",
                    SOURCE_BULK,
                    suppressRepopulation
                            ? "entity replay completed without failures; cleared legacy pending payloads"
                            : "entity replay completed without failures; retained durable entity payloads"
            );
        } else {
            PayloadWatchTracer.traceEntityReplayState(
                    world, chunk, runtimeDelta, operationId,
                    ChunkTraceEventType.WATCH_SKIPPED, "entity-replay-retained-pending",
                    SOURCE_BULK, "entity replay retained pending payloads after failures=" + failures
            );
        }
    }

    /**
     * Replays a single pending entity payload, identified by {@code entityUuid},
     * from {@code runtimeDelta} or {@code fallbackEntityNbt}.
     *
     * <p>Used by {@link ScheduledEntityReplayQueue} when retrying a previously
     * deferred entity. The result status indicates whether the caller should
     * remove the entry from the queue, retry later, or give up permanently.</p>
     *
     * @param world             target server world; defensive null-check applied
     * @param chunk             target chunk; defensive null-check applied
     * @param runtimeDelta      delta containing pending entity payloads; may be {@code null}
     * @param operationId       save-operation correlation ID for tracing; may be {@code null}
     * @param entityUuid        UUID string of the entity to replay; defensive null/blank check applied
     * @param fallbackEntityNbt fallback NBT used when the entity is not found in {@code runtimeDelta}; may be
     *                          {@code null}
     * @return replay result describing outcome and whether to retry
     */
    static ChunkRestorer.ReplayResult replayPendingEntityIfNeeded(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            @Nullable final String operationId,
            final String entityUuid,
            @Nullable final NbtCompound fallbackEntityNbt
    ) {
        if (world == null || chunk == null || entityUuid == null || entityUuid.isBlank()) {
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PERMANENT_FAILURE, "missing world/chunk/entity uuid"
            );
        }
        if ((runtimeDelta == null || runtimeDelta.countPendingEntities() == 0) && fallbackEntityNbt == null) {
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world, chunk.getPos(), entityUuid, operationId,
                    "entity replay skipped: runtime delta missing queued entity payloads"
            );
            traceEntityReplayDecision(
                    world, chunk, null, entityUuid, null, false, "payload-missing"
            );
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PAYLOAD_MISSING,
                    "runtime delta missing queued entity payloads"
            );
        }

        NbtCompound entityNbt = runtimeDelta == null ? null : findPendingEntityNbt(runtimeDelta, entityUuid);
        if (entityNbt == null) {
            entityNbt = fallbackEntityNbt;
        }
        if (entityNbt == null) {
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world, chunk.getPos(), entityUuid, operationId,
                    "entity replay skipped: queued entity payload missing from runtime delta"
            );
            traceEntityReplayDecision(
                    world, chunk, runtimeDelta, entityUuid, null, false, "payload-missing-for-uuid"
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
                    world, chunk.getPos(), entityUuid, operationId,
                    "entity replay skipped: queued entity UUID was invalid"
            );
            traceEntityReplayDecision(
                    world, chunk, runtimeDelta, entityUuid, entityNbt, false, "invalid-uuid"
            );
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PERMANENT_FAILURE, "queued entity UUID was invalid"
            );
        }

        final ChunkPos chunkPosition = chunk.getPos();
        if (world.getChunkManager().getWorldChunk(chunkPosition.x, chunkPosition.z, false) == null) {
            traceEntityReplayDecision(
                    world, chunk, runtimeDelta, entityUuid, entityNbt, false, "chunk-not-loaded"
            );
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.CHUNK_NOT_READY, "target chunk is not loaded"
            );
        }

        PayloadWatchTracer.traceEntityReplayState(
                world, chunk, runtimeDelta, operationId,
                ChunkTraceEventType.WATCH_CAPTURED, "entity-replay-started",
                SOURCE_SINGLE, "entity replay started from runtime delta"
        );
        PayloadWatchTracer.traceRestoreEntityApplyAttempt(
                world, chunkPosition, entityNbt, operationId, SOURCE_SINGLE
        );

        final NbtCompound replayNbt = entityNbt;
        final Box searchBox = chunkEntitySearchBox(world, chunkPosition);
        final ChunkRestorer.ReplayStatus[] callbackStatus = { ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT };

        EntityType.loadEntityWithPassengers(
                replayNbt, world, SpawnReason.LOAD, entity -> {
                    final SpawnOutcome outcome = attemptEntitySpawn(
                            world, chunkPosition, searchBox, entity, replayNbt, operationId
                    );
                    callbackStatus[0] = outcome.status();
                    if (outcome.shouldSchedule()) {
                        ScheduledEntityReplayQueue.schedule(
                                world, chunkPosition, entity.getUuidAsString(), replayNbt
                        );
                    }
                    return entity;
                }
        );

        // Post-spawn verification: re-query the world to confirm the entity is
        // actually present and in the expected chunk. The callback result alone
        // is insufficient because spawnEntity can report success before the
        // entity is visible from spatial queries.
        final Entity afterReplay = world.getEntity(queuedUuid);
        final boolean materialized = afterReplay != null
                && isMatchingLiveEntity(afterReplay, entityTypeFromNbt(replayNbt).orElse(null), chunkPosition);
        final boolean visibleAfterReplay = isVisibleFromWorldQuery(world, queuedUuid, searchBox);
        final boolean confirmed = materialized && visibleAfterReplay;

        PayloadWatchTracer.traceEntityReplayState(
                world, chunk, runtimeDelta, operationId,
                confirmed ? ChunkTraceEventType.WATCH_CAPTURED : ChunkTraceEventType.WATCH_SKIPPED,
                "entity-replay-retained-pending",
                SOURCE_SINGLE,
                confirmed
                        ? "entity replay resolved queued entity payload"
                        : callbackStatus[0] == ChunkRestorer.ReplayStatus.ALREADY_PRESENT
                          ? "entity replay callback resolved but queued UUID was still absent from live world"
                          : "entity replay retained queued entity payload after unresolved spawn"
        );
        traceEntityReplayDecision(
                world, chunk, runtimeDelta, entityUuid, replayNbt, confirmed,
                "after-spawn materialized=" + materialized + " visible=" + visibleAfterReplay
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
        // If the callback said ALREADY_PRESENT but the post-spawn world query
        // disagrees, the entity is not safely confirmed — treat as transient
        // failure so the scheduler can retry.
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
     * {@code forEachPendingEntity} does not support early exit; instead, the
     * callback skips processing after the first miss.</p>
     *
     * @param world         server world to query
     * @param chunkPosition expected chunk location for each entity
     * @param runtimeDelta  delta whose pending entities are checked
     * @return {@code true} if all pending entities are visible, or if there are none
     */
    static boolean allPendingEntitiesVisible(
            final ServerWorld world,
            final ChunkPos chunkPosition,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta
    ) {
        if (runtimeDelta.countPendingEntities() == 0) {
            return true;
        }
        final Box searchBox = chunkEntitySearchBox(world, chunkPosition);
        final boolean[] allVisible = { true };
        runtimeDelta.forEachPendingEntity(nbt -> {
            if (!allVisible[0] || nbt == null) {
                return;
            }
            final UUID uuid = nbt.getIntArray(UUID_KEY)
                    .flatMap(EntityReplayCoordinator::safeUuidFromIntArray)
                    .orElse(null);
            allVisible[0] = uuid != null && isVisibleFromWorldQuery(world, uuid, searchBox);
        });
        return allVisible[0];
    }

    /**
     * Attempts to spawn {@code entity} into {@code world} and classifies the
     * outcome as one of the {@link ChunkRestorer.ReplayStatus} values.
     *
     * <p>Three branches:</p>
     * <ol>
     *   <li>A matching live entity with the same UUID is already present and
     *       visible — treated as {@link ChunkRestorer.ReplayStatus#ALREADY_PRESENT}.</li>
     *   <li>A live entity with the same UUID exists but is a type or chunk
     *       mismatch — {@link ChunkRestorer.ReplayStatus#DUPLICATE_UUID_CONFLICT}.</li>
     *   <li>No existing entity: {@code spawnEntity} is called. If the entity
     *       becomes visible the outcome is {@link ChunkRestorer.ReplayStatus#SPAWNED};
     *       otherwise {@link ChunkRestorer.ReplayStatus#SPAWN_REJECTED_TRANSIENT}.</li>
     * </ol>
     *
     * <p>This method is shared by both the bulk and single-entity replay paths
     * to avoid duplicating the branching logic.</p>
     *
     * @param world         target world
     * @param chunkPosition expected chunk for the entity
     * @param searchBox     spatial query box covering the chunk (pre-computed by caller)
     * @param entity        deserialized entity, not yet in the world
     * @param nbt           original NBT of the entity, used for trace calls
     * @param operationId   correlation ID for tracing; may be {@code null}
     * @return outcome descriptor
     */
    private static SpawnOutcome attemptEntitySpawn(
            final ServerWorld world,
            final ChunkPos chunkPosition,
            final Box searchBox,
            final Entity entity,
            final NbtCompound nbt,
            @Nullable final String operationId
    ) {
        final Entity existing = world.getEntity(entity.getUuid());

        if (isMatchingLiveEntity(existing, entity.getType(), chunkPosition) && isVisibleFromWorldQuery(
                world,
                entity.getUuid(), searchBox
        )) {
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world, chunkPosition, entity.getUuidAsString(), operationId,
                    "restore skipped: entity already present in world"
            );
            return SpawnOutcome.ALREADY_PRESENT;
        }

        if (existing != null) {
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world, chunkPosition, entity.getUuidAsString(), operationId,
                    "restore skipped: duplicate UUID conflict type/chunk mismatch"
            );
            return SpawnOutcome.DUPLICATE_UUID_CONFLICT;
        }

        if (world.spawnEntity(entity)) {
            if (isVisibleFromWorldQuery(world, entity.getUuid(), searchBox)) {
                PayloadWatchTracer.traceRestoredEntity(world, chunkPosition, entity, nbt, operationId);
                return SpawnOutcome.SPAWNED;
            }
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world, chunkPosition, entity.getUuidAsString(), operationId,
                    "restore skipped: spawnEntity returned true but world query did not find entity"
            );
            return SpawnOutcome.SPAWN_ACCEPTED_NOT_VISIBLE;
        }

        PayloadWatchTracer.traceRestoreEntitySkipped(
                world, chunkPosition, entity.getUuidAsString(), operationId,
                "restore skipped: ServerWorld.spawnEntity returned false"
        );
        return SpawnOutcome.SPAWN_REJECTED;
    }

    /**
     * Outcome of a single entity spawn attempt, carrying the resolved
     * {@link ChunkRestorer.ReplayStatus} and scheduling intent.
     */
    private enum SpawnOutcome {
        /**
         * Entity with matching UUID/type/chunk already confirmed in world — no action needed.
         */
        ALREADY_PRESENT(ChunkRestorer.ReplayStatus.ALREADY_PRESENT, false, true),
        /**
         * UUID exists but belongs to a different type or chunk — cannot spawn.
         */
        DUPLICATE_UUID_CONFLICT(ChunkRestorer.ReplayStatus.DUPLICATE_UUID_CONFLICT, false, false),
        /**
         * Entity spawned and visible from spatial query.
         */
        SPAWNED(ChunkRestorer.ReplayStatus.SPAWNED, true, false),
        /**
         * {@code spawnEntity} accepted the entity but it is not yet visible —
         * schedule for retry.
         */
        SPAWN_ACCEPTED_NOT_VISIBLE(ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT, false, true),
        /**
         * {@code spawnEntity} returned false — schedule for retry.
         */
        SPAWN_REJECTED(ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT, false, true);

        private final ChunkRestorer.ReplayStatus status;
        /**
         * Whether the entity was successfully confirmed in the world.
         */
        private final boolean succeeded;
        /**
         * Whether the entity should be forwarded to {@link ScheduledEntityReplayQueue}.
         */
        private final boolean shouldSchedule;

        SpawnOutcome(
                final ChunkRestorer.ReplayStatus status,
                final boolean succeeded,
                final boolean shouldSchedule
        ) {
            this.status = status;
            this.succeeded = succeeded;
            this.shouldSchedule = shouldSchedule;
        }

        ChunkRestorer.ReplayStatus status() {return status;}

        boolean succeeded() {return succeeded;}

        boolean shouldSchedule() {return shouldSchedule;}
    }

    /**
     * Scans pending entities in {@code runtimeDelta} for one whose UUID matches
     * {@code entityUuid}, and returns its NBT.
     *
     * <p>Cannot short-circuit once found because {@code forEachPendingEntity}
     * does not support early exit; the callback skips work after the first match.</p>
     *
     * @param runtimeDelta delta to scan
     * @param entityUuid   UUID string to match
     * @return NBT of the matching entity, or {@code null} if not found
     */
    @Nullable
    private static NbtCompound findPendingEntityNbt(
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final String entityUuid
    ) {
        final NbtCompound[] found = { null };
        runtimeDelta.forEachPendingEntity(nbt -> {
            if (found[0] != null || nbt == null) {
                return;
            }
            final String candidateUuid = nbt.getIntArray(UUID_KEY)
                    .flatMap(EntityReplayCoordinator::safeUuidFromIntArray)
                    .map(UUID::toString)
                    .orElse(null);
            if (entityUuid.equals(candidateUuid)) {
                found[0] = nbt;
            }
        });
        return found[0];
    }

    /**
     * Emits a detailed diagnostic log line for a single-entity replay decision.
     *
     * <p>This is a verbose developer-facing log, always emitted at {@code INFO}
     * level regardless of debug configuration. It is intended for diagnosing
     * entity materialization issues in the field.</p>
     *
     * @param world        server world
     * @param chunk        target chunk
     * @param runtimeDelta runtime delta at time of decision; may be {@code null}
     * @param entityUuid   UUID string being replayed
     * @param entityNbt    entity NBT at time of decision; may be {@code null}
     * @param materialized whether the entity was confirmed in the world after replay
     * @param decision     short human-readable label for the decision branch taken
     */
    private static void traceEntityReplayDecision(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final String entityUuid,
            @Nullable final NbtCompound entityNbt,
            final boolean materialized,
            final String decision
    ) {
        final ChunkPos chunkPos = chunk.getPos();
        final UUID uuid = parseUuid(entityUuid).orElse(null);
        final Entity liveEntity = uuid == null ? null : world.getEntity(uuid);
        final Identifier nbtType = entityNbt == null
                ? null
                : entityNbt.getString(ID_KEY).map(Identifier::tryParse).orElse(null);
        final BlockPos nbtBlockPos = blockPosFromNbt(entityNbt).orElse(null);
        final boolean chunkLoaded = world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false) != null;
        final boolean entityTicking = nbtBlockPos != null && world.shouldTickEntityAt(nbtBlockPos);
        final ChunkMutationTrackingScope.Cause pendingSuppression =
                PendingChunkMutationSuppression.currentCause(world.getRegistryKey(), chunkPos);
        final ChunkMutationTrackingScope.Cause chunkSuppression =
                chunk instanceof ChunkisMutationGuardDuck guardDuck
                        ? guardDuck.chunkis$getMutationTrackingScope().currentCause()
                        : ChunkMutationTrackingScope.Cause.NONE;
        // Re-use search box so this trace call doesn't issue an extra spatial query.
        final Box searchBox = chunkEntitySearchBox(world, chunkPos);
        final boolean visibleByQuery = uuid != null && isVisibleFromWorldQuery(world, uuid, searchBox);

        Chunkis.LOGGER.info(
                "Chunkis entity replay materialization: dimension={} chunk={},{} uuid={} nbtType={} nbtBlockPos={} " +
                        "nbtPos={} liveExists={} liveType={} liveChunk={} chunkLoaded={} entityTicking={} " +
                        "mutationSuppression={} chunkSuppression={} pendingBefore={} queueSize={} materialized={} " +
                        "visibleByWorldQuery={} decision={} thread={}",
                world.getRegistryKey().getValue(),
                chunkPos.x, chunkPos.z,
                entityUuid,
                nbtType,
                nbtBlockPos == null ? "<missing>" : nbtBlockPos.toShortString(),
                entityNbt == null ? "<missing>" : entityNbt.getList("Pos").map(Object::toString).orElse("<missing>"),
                liveEntity != null && liveEntity.isAlive() && !liveEntity.isRemoved(),
                liveEntity == null ? "<missing>" : liveEntity.getType(),
                liveEntity == null ? "<missing>" : liveEntity.getChunkPos().x + "," + liveEntity.getChunkPos().z,
                chunkLoaded,
                entityTicking,
                pendingSuppression,
                chunkSuppression,
                runtimeDelta == null ? -1 : runtimeDelta.countPendingEntities(),
                ScheduledEntityReplayQueue.size(),
                materialized,
                visibleByQuery,
                decision,
                Thread.currentThread().getName()
        );
    }

    /**
     * Parses {@code entityUuid} into a {@link UUID}, returning empty on failure.
     *
     * @param entityUuid UUID string to parse
     * @return parsed UUID, or empty if the string is not a valid UUID
     */
    private static Optional<UUID> parseUuid(final String entityUuid) {
        try {
            return Optional.of(UUID.fromString(entityUuid));
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Extracts the entity type from {@code nbt} using the {@value #ID_KEY} field.
     *
     * @param nbt entity NBT; may be {@code null}
     * @return entity type, or empty if missing or unregistered
     */
    private static Optional<EntityType<?>> entityTypeFromNbt(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }
        return nbt.getString(ID_KEY)
                .map(Identifier::tryParse)
                .map(Registries.ENTITY_TYPE::get);
    }

    /**
     * Extracts the block position nearest to the entity's stored {@code Pos}
     * list in {@code nbt}.
     *
     * @param nbt entity NBT; may be {@code null}
     * @return floored block position, or empty if missing or malformed
     */
    private static Optional<BlockPos> blockPosFromNbt(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }
        return nbt.getList("Pos")
                .filter(pos -> pos.size() >= 3)
                .map(pos -> new BlockPos(
                        (int) Math.floor(pos.getDouble(0).orElse(0.0D)),
                        (int) Math.floor(pos.getDouble(1).orElse(0.0D)),
                        (int) Math.floor(pos.getDouble(2).orElse(0.0D))
                ));
    }

    /**
     * Returns {@code true} if {@code entity} is alive, not removed, of the
     * expected type, and located in the expected chunk.
     *
     * @param entity        live entity to check
     * @param expectedType  expected entity type; if {@code null}, always returns false
     * @param expectedChunk expected chunk position
     * @return {@code true} if all conditions hold
     */
    private static boolean isMatchingLiveEntity(
            final Entity entity,
            @Nullable final EntityType<?> expectedType,
            final ChunkPos expectedChunk
    ) {
        return entity != null
                && entity.isAlive()
                && !entity.isRemoved()
                && expectedType != null
                && entity.getType() == expectedType
                && expectedChunk.equals(entity.getChunkPos());
    }

    /**
     * Returns {@code true} if an alive, non-removed entity with {@code uuid}
     * appears in a spatial query over {@code searchBox}.
     *
     * <p>The spatial query is the authoritative visibility check. An entity
     * returned by {@link ServerWorld#getEntity(UUID)} but absent from this
     * query is not considered safely materialized.</p>
     *
     * @param world     world to query
     * @param uuid      UUID of the entity to look for
     * @param searchBox pre-computed bounding box to search (avoids reallocation per call)
     * @return {@code true} if the entity is visible from the world's entity system
     */
    private static boolean isVisibleFromWorldQuery(
            final ServerWorld world,
            final UUID uuid,
            final Box searchBox
    ) {
        for (final Entity entity : world.getOtherEntities(null, searchBox)) {
            if (uuid.equals(entity.getUuid()) && entity.isAlive() && !entity.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a {@link Box} covering the full horizontal extent of {@code chunkPosition}
     * and the full vertical extent of {@code world}.
     *
     * <p>Used as the search bounds for {@link #isVisibleFromWorldQuery}.</p>
     *
     * @param world         world providing {@code bottomY} and {@code height}
     * @param chunkPosition target chunk
     * @return bounding box for the chunk column
     */
    private static Box chunkEntitySearchBox(final ServerWorld world, final ChunkPos chunkPosition) {
        return new Box(
                chunkPosition.getStartX(), world.getBottomY(), chunkPosition.getStartZ(),
                chunkPosition.getEndX() + 1, world.getBottomY() + world.getHeight(), chunkPosition.getEndZ() + 1
        );
    }

    /**
     * Converts a raw 4-element int array to a {@link UUID}, returning empty
     * on failure.
     *
     * @param rawUuid 4-element int array as stored by {@link net.minecraft.util.Uuids}
     * @return parsed UUID, or empty if the array is invalid
     */
    private static Optional<UUID> safeUuidFromIntArray(final int[] rawUuid) {
        try {
            return Optional.of(Uuids.toUuid(rawUuid));
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}