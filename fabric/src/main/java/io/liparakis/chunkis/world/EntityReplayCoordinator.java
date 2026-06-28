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

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class EntityReplayCoordinator {

    private static final String ID_KEY = "id";
    private static final String UUID_KEY = "UUID";

    private EntityReplayCoordinator() {
        throw new AssertionError("Utility class");
    }

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
                    world,
                    chunk,
                    null,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "entity-replay-no-runtime-delta",
                    "EntityReplayCoordinator#replayPendingEntitiesIfNeeded",
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
                    "EntityReplayCoordinator#replayPendingEntitiesIfNeeded",
                    "entity replay skipped: runtime delta has no pending entity payloads"
            );
            return;
        }

        final ChunkPos chunkPosition = chunk.getPos();
        PayloadWatchTracer.traceEntityReplayState(
                world,
                chunk,
                runtimeDelta,
                operationId,
                ChunkTraceEventType.WATCH_CAPTURED,
                "entity-replay-started",
                "EntityReplayCoordinator#replayPendingEntitiesIfNeeded",
                "entity replay started from runtime delta"
        );
        final int[] replayStats = {0, 0};
        runtimeDelta.forEachPendingEntity(nbt -> {
            if (nbt == null) {
                return;
            }
            PayloadWatchTracer.traceRestoreEntityApplyAttempt(
                    world,
                    chunkPosition,
                    nbt,
                    operationId,
                    "EntityReplayCoordinator#replayPendingEntitiesIfNeeded"
            );

            EntityType.loadEntityWithPassengers(
                    nbt,
                    world,
                    SpawnReason.LOAD,
                    entity -> {
                        final Entity existing = world.getEntity(entity.getUuid());
                        if (existing != null
                                && isMatchingLiveEntity(existing, entity.getType(), chunkPosition)
                                && isVisibleFromWorldQuery(world, entity.getUuid(), chunkPosition)) {
                            replayStats[0]++;
                            ScheduledEntityReplayQueue.acknowledge(entity.getUuidAsString());
                            PayloadWatchTracer.traceRestoreEntitySkipped(
                                    world,
                                    chunkPosition,
                                    entity.getUuidAsString(),
                                    operationId,
                                    "restore skipped: entity already present in world"
                            );
                        } else if (existing != null) {
                            replayStats[1]++;
                            PayloadWatchTracer.traceRestoreEntitySkipped(
                                    world,
                                    chunkPosition,
                                    entity.getUuidAsString(),
                                    operationId,
                                    "restore skipped: duplicate UUID conflict type/chunk mismatch"
                            );
                        } else if (world.spawnEntity(entity)) {
                            if (isVisibleFromWorldQuery(world, entity.getUuid(), chunkPosition)) {
                                replayStats[0]++;
                                ScheduledEntityReplayQueue.acknowledge(entity.getUuidAsString());
                                PayloadWatchTracer.traceRestoredEntity(world, chunkPosition, entity, nbt, operationId);
                            } else {
                                replayStats[1]++;
                                ScheduledEntityReplayQueue.schedule(
                                        world,
                                        chunkPosition,
                                        entity.getUuidAsString(),
                                        nbt
                                );
                                PayloadWatchTracer.traceRestoreEntitySkipped(
                                        world,
                                        chunkPosition,
                                        entity.getUuidAsString(),
                                        operationId,
                                        "restore skipped: spawnEntity returned true but world query did not find entity"
                                );
                            }
                        } else {
                            replayStats[1]++;
                            ScheduledEntityReplayQueue.schedule(
                                    world,
                                    chunkPosition,
                                    entity.getUuidAsString(),
                                    nbt
                            );
                            PayloadWatchTracer.traceRestoreEntitySkipped(
                                    world,
                                    chunkPosition,
                                    entity.getUuidAsString(),
                                    operationId,
                                    "restore skipped: ServerWorld.spawnEntity returned false"
                            );
                        }
                        return entity;
                    }
            );
        });

        if (replayStats[1] == 0) {
            if (runtimeDelta.shouldSuppressInitialRepopulation()) {
                runtimeDelta.clearPendingEntities();
            }
            PayloadWatchTracer.traceEntityReplayState(
                    world,
                    chunk,
                    runtimeDelta,
                    operationId,
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "entity-replay-retained-pending",
                    "EntityReplayCoordinator#replayPendingEntitiesIfNeeded",
                    runtimeDelta.shouldSuppressInitialRepopulation()
                            ? "entity replay completed without failures; cleared legacy pending payloads"
                            : "entity replay completed without failures; retained durable entity payloads"
            );
            return;
        }
        PayloadWatchTracer.traceEntityReplayState(
                world,
                chunk,
                runtimeDelta,
                operationId,
                ChunkTraceEventType.WATCH_SKIPPED,
                "entity-replay-retained-pending",
                "EntityReplayCoordinator#replayPendingEntitiesIfNeeded",
                "entity replay retained pending payloads after failures=" + replayStats[1]
        );
    }

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
            traceEntityReplayDecision(world, chunk, null, entityUuid, null, false, "payload-missing");
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PAYLOAD_MISSING,
                    "runtime delta missing queued entity payloads"
            );
        }

        NbtCompound entityNbt = runtimeDelta == null ? null : findPendingEntityNbt(runtimeDelta, entityUuid);
        if (entityNbt == null && fallbackEntityNbt != null) {
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
            traceEntityReplayDecision(world, chunk, runtimeDelta, entityUuid, null, false, "payload-missing-for-uuid");
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PAYLOAD_MISSING,
                    "queued entity payload missing from runtime delta"
            );
        }

        final ChunkPos chunkPosition = chunk.getPos();
        PayloadWatchTracer.traceEntityReplayState(
                world,
                chunk,
                runtimeDelta,
                operationId,
                ChunkTraceEventType.WATCH_CAPTURED,
                "entity-replay-started",
                "EntityReplayCoordinator#replayPendingEntityIfNeeded",
                "entity replay started from runtime delta"
        );
        PayloadWatchTracer.traceRestoreEntityApplyAttempt(
                world,
                chunkPosition,
                entityNbt,
                operationId,
                "EntityReplayCoordinator#replayPendingEntityIfNeeded"
        );

        final boolean[] resolved = {false};
        final ChunkRestorer.ReplayStatus[] callbackStatus = {ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT};
        final UUID queuedUuid;
        try {
            queuedUuid = UUID.fromString(entityUuid);
        } catch (final IllegalArgumentException ignored) {
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world,
                    chunkPosition,
                    entityUuid,
                    operationId,
                    "entity replay skipped: queued entity UUID was invalid"
            );
            traceEntityReplayDecision(world, chunk, runtimeDelta, entityUuid, entityNbt, false, "invalid-uuid");
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.PERMANENT_FAILURE,
                    "queued entity UUID was invalid"
            );
        }
        if (world.getChunkManager().getWorldChunk(chunkPosition.x, chunkPosition.z, false) == null) {
            traceEntityReplayDecision(world, chunk, runtimeDelta, entityUuid, entityNbt, false, "chunk-not-loaded");
            return new ChunkRestorer.ReplayResult(
                    ChunkRestorer.ReplayStatus.CHUNK_NOT_READY,
                    "target chunk is not loaded"
            );
        }

        final NbtCompound replayEntityNbt = entityNbt;
        EntityType.loadEntityWithPassengers(
                replayEntityNbt,
                world,
                SpawnReason.LOAD,
                entity -> {
                    final Entity existing = world.getEntity(entity.getUuid());
                    if (existing != null
                            && isMatchingLiveEntity(existing, entity.getType(), chunkPosition)
                            && isVisibleFromWorldQuery(world, entity.getUuid(), chunkPosition)) {
                        resolved[0] = true;
                        callbackStatus[0] = ChunkRestorer.ReplayStatus.ALREADY_PRESENT;
                        PayloadWatchTracer.traceRestoreEntitySkipped(
                                world,
                                chunkPosition,
                                entity.getUuidAsString(),
                                operationId,
                                "restore skipped: entity already present in world"
                        );
                    } else if (existing != null) {
                        callbackStatus[0] = ChunkRestorer.ReplayStatus.DUPLICATE_UUID_CONFLICT;
                        PayloadWatchTracer.traceRestoreEntitySkipped(
                                world,
                                chunkPosition,
                                entity.getUuidAsString(),
                                operationId,
                                "restore skipped: duplicate UUID conflict type/chunk mismatch"
                        );
                    } else if (world.spawnEntity(entity)) {
                        final boolean visible = isVisibleFromWorldQuery(world, entity.getUuid(), chunkPosition);
                        resolved[0] = visible;
                        if (visible) {
                            callbackStatus[0] = ChunkRestorer.ReplayStatus.SPAWNED;
                            PayloadWatchTracer.traceRestoredEntity(
                                    world,
                                    chunkPosition,
                                    entity,
                                    replayEntityNbt,
                                    operationId
                            );
                        } else {
                            callbackStatus[0] = ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT;
                            ScheduledEntityReplayQueue.schedule(
                                    world,
                                    chunkPosition,
                                    entity.getUuidAsString(),
                                    replayEntityNbt
                            );
                            PayloadWatchTracer.traceRestoreEntitySkipped(
                                    world,
                                    chunkPosition,
                                    entity.getUuidAsString(),
                                    operationId,
                                    "restore skipped: spawnEntity returned true but world query did not find entity"
                            );
                        }
                    } else {
                        callbackStatus[0] = ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT;
                        ScheduledEntityReplayQueue.schedule(
                                world,
                                chunkPosition,
                                entity.getUuidAsString(),
                                replayEntityNbt
                        );
                        PayloadWatchTracer.traceRestoreEntitySkipped(
                                world,
                                chunkPosition,
                                entity.getUuidAsString(),
                                operationId,
                                "restore skipped: ServerWorld.spawnEntity returned false"
                        );
                    }
                    return entity;
                }
        );

        final Entity presentAfterReplay = world.getEntity(queuedUuid);
        final boolean materialized = presentAfterReplay != null
                && isMatchingLiveEntity(
                presentAfterReplay,
                entityTypeFromNbt(replayEntityNbt).orElse(null),
                chunkPosition
        );
        final boolean visibleAfterReplay = isVisibleFromWorldQuery(world, queuedUuid, chunkPosition);

        PayloadWatchTracer.traceEntityReplayState(
                world,
                chunk,
                runtimeDelta,
                operationId,
                materialized && visibleAfterReplay
                        ? ChunkTraceEventType.WATCH_CAPTURED
                        : ChunkTraceEventType.WATCH_SKIPPED,
                "entity-replay-retained-pending",
                "EntityReplayCoordinator#replayPendingEntityIfNeeded",
                materialized && visibleAfterReplay
                        ? "entity replay resolved queued entity payload"
                        : resolved[0]
                        ? "entity replay callback resolved but queued UUID was still absent from live world"
                        : "entity replay retained queued entity payload after unresolved spawn"
        );
        traceEntityReplayDecision(
                world,
                chunk,
                runtimeDelta,
                entityUuid,
                replayEntityNbt,
                materialized && visibleAfterReplay,
                "after-spawn materialized=" + materialized + " visible=" + visibleAfterReplay
        );
        if (materialized && visibleAfterReplay) {
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

    static boolean allPendingEntitiesVisible(
            final ServerWorld world,
            final ChunkPos chunkPosition,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta
    ) {
        if (runtimeDelta.countPendingEntities() == 0) {
            return true;
        }
        final boolean[] allVisible = {true};
        runtimeDelta.forEachPendingEntity(nbt -> {
            if (!allVisible[0] || nbt == null) {
                return;
            }
            final UUID uuid = nbt.getIntArray(UUID_KEY)
                    .flatMap(EntityReplayCoordinator::safeUuidFromIntArray)
                    .orElse(null);
            allVisible[0] = uuid != null && isVisibleFromWorldQuery(world, uuid, chunkPosition);
        });
        return allVisible[0];
    }

    @Nullable
    private static NbtCompound findPendingEntityNbt(
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final String entityUuid
    ) {
        final NbtCompound[] found = {null};
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

        Chunkis.LOGGER.info(
                "Chunkis entity replay materialization: dimension={} chunk={},{} uuid={} nbtType={} nbtBlockPos={} nbtPos={} liveExists={} liveType={} liveChunk={} chunkLoaded={} entityTicking={} mutationSuppression={} chunkSuppression={} pendingBefore={} queueSize={} materialized={} visibleByWorldQuery={} decision={} thread={}",
                world.getRegistryKey().getValue(),
                chunkPos.x,
                chunkPos.z,
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
                uuid != null && isVisibleFromWorldQuery(world, uuid, chunkPos),
                decision,
                Thread.currentThread().getName()
        );
    }

    private static Optional<UUID> parseUuid(final String entityUuid) {
        try {
            return Optional.of(UUID.fromString(entityUuid));
        } catch (final RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<EntityType<?>> entityTypeFromNbt(final NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }
        return nbt.getString(ID_KEY)
                .map(Identifier::tryParse)
                .filter(Objects::nonNull)
                .map(Registries.ENTITY_TYPE::get);
    }

    private static Optional<BlockPos> blockPosFromNbt(final NbtCompound nbt) {
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

    private static boolean isVisibleFromWorldQuery(
            final ServerWorld world,
            final UUID uuid,
            final ChunkPos expectedChunk
    ) {
        for (final Entity entity : world.getOtherEntities(null, chunkEntitySearchBox(world, expectedChunk))) {
            if (uuid.equals(entity.getUuid()) && entity.isAlive() && !entity.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    private static Box chunkEntitySearchBox(final ServerWorld world, final ChunkPos chunkPosition) {
        return new Box(
                chunkPosition.getStartX(),
                world.getBottomY(),
                chunkPosition.getStartZ(),
                chunkPosition.getEndX() + 1,
                world.getBottomY() + world.getHeight(),
                chunkPosition.getEndZ() + 1
        );
    }

    private static Optional<UUID> safeUuidFromIntArray(final int[] rawUuid) {
        try {
            return Optional.of(Uuids.toUuid(rawUuid));
        } catch (final RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
