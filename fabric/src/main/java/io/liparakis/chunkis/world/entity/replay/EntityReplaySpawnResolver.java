package io.liparakis.chunkis.world.entity.replay;

import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityQueries;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves duplicate detection and spawn visibility for one replayed entity.
 *
 * <p>This keeps the coordinator focused on payload selection and retry flow
 * while centralizing the exact rules for classifying one spawn attempt.</p>
 */
final class EntityReplaySpawnResolver {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private EntityReplaySpawnResolver() {
        throw new AssertionError("Utility class");
    }

    /**
     * Attempts to materialize one entity from replay data.
     *
     * @param world         target world
     * @param chunkPosition expected chunk for the entity
     * @param searchBox     precomputed chunk-column query box
     * @param entity        deserialized entity, not yet in the world
     * @param nbt           original entity NBT used for tracing
     * @param operationId   trace correlation ID; may be {@code null}
     * @return spawn outcome including retry intent
     */
    static SpawnOutcome attempt(final ServerLevel world, final ChunkPos chunkPosition, final AABB searchBox,
            final Entity entity, final CompoundTag nbt, @Nullable final String operationId) {
        final Entity existing = world.getEntity(entity.getUUID());

        if (ChunkEntityQueries.isMatchingLiveEntity(existing, entity.getType(), chunkPosition)) {
            PayloadWatchTracer.traceRestoreEntitySkipped(world, chunkPosition, entity.getStringUUID(), operationId,
                    "restore skipped: entity already present in world");
            return SpawnOutcome.ALREADY_PRESENT;
        }

        if (existing != null) {
            PayloadWatchTracer.traceRestoreEntitySkipped(world, chunkPosition, entity.getStringUUID(), operationId,
                    "restore skipped: duplicate UUID conflict type/chunk mismatch");
            return SpawnOutcome.DUPLICATE_UUID_CONFLICT;
        }

        world.addLegacyChunkEntities(Stream.of(entity));
        if (ChunkEntityQueries.isVisibleFromWorldQuery(world, entity.getUUID(), searchBox)
                || ChunkEntityQueries.isMatchingLiveEntity(world.getEntity(entity.getUUID()), entity.getType(),
                chunkPosition)) {
            PayloadWatchTracer.traceRestoredEntity(world, chunkPosition, entity, nbt, operationId);
            return SpawnOutcome.SPAWNED;
        }
        PayloadWatchTracer.traceRestoreEntitySkipped(world,
                chunkPosition,
                entity.getStringUUID(),
                operationId,
                "restore skipped: loaded entity was not visible in world"
                        + " entityChunk=" + entity.chunkPosition()
                        + " entityPos=" + entity.blockPosition()
                        + " removed=" + entity.isRemoved()
                        + " alive=" + entity.isAlive()
                        + " nbtPos=" + EntityPayloadNbt.describeRawPos(nbt));
        return SpawnOutcome.SPAWN_ACCEPTED_NOT_VISIBLE;
    }

    /**
     * Outcome of one entity spawn attempt.
     *
     * <p>{@link #ALREADY_PRESENT} intentionally behaves like a retryable
     * non-success so the scheduled queue can clean the stale pending entry on
     * the next tick.</p>
     */
    enum SpawnOutcome {
        /**
         * Entity is already present in the world.
         */
        ALREADY_PRESENT(ChunkRestorer.ReplayStatus.ALREADY_PRESENT, false, true),

        /**
         * Target UUID duplicates an existing entity of a different type/chunk.
         */
        DUPLICATE_UUID_CONFLICT(ChunkRestorer.ReplayStatus.DUPLICATE_UUID_CONFLICT, false, false),

        /**
         * Entity was successfully spawned.
         */
        SPAWNED(ChunkRestorer.ReplayStatus.SPAWNED, true, false),

        /**
         * Spawn entity was accepted by the world but remains invisible to spatial query.
         */
        SPAWN_ACCEPTED_NOT_VISIBLE(ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT, false, true),

        /**
         * Spawn entity request was rejected.
         */
        SPAWN_REJECTED(ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT,
                false,
                true); //TODO: Implement functionality if needed

        /**
         * Replay status associated with the outcome.
         */
        private final ChunkRestorer.ReplayStatus status;

        /**
         * True if the spawn succeeded.
         */
        private final boolean succeeded;

        /**
         * True if the spawn task should be scheduled for retry.
         */
        private final boolean shouldSchedule;

        /**
         * Constructor for SpawnOutcome.
         *
         * @param status         replay status
         * @param succeeded      success state
         * @param shouldSchedule schedule state
         */
        SpawnOutcome(final ChunkRestorer.ReplayStatus status, final boolean succeeded, final boolean shouldSchedule) {
            this.status = status;
            this.succeeded = succeeded;
            this.shouldSchedule = shouldSchedule;
        }

        /**
         * Returns the replay status.
         *
         * @return replay status
         */
        ChunkRestorer.ReplayStatus status() {
            return status;
        }

        /**
         * Returns whether the spawn succeeded.
         *
         * @return true if succeeded
         */
        boolean succeeded() {
            return succeeded;
        }

        /**
         * Returns whether the spawn should be scheduled.
         *
         * @return true if should schedule
         */
        boolean shouldSchedule() {
            return shouldSchedule;
        }
    }
}
