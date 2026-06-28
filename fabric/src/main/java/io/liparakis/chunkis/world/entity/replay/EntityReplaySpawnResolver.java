package io.liparakis.chunkis.world.entity.replay;

import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityQueries;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves duplicate detection and spawn visibility for one replayed entity.
 *
 * <p>This keeps the coordinator focused on payload selection and retry flow
 * while centralizing the exact rules for classifying one spawn attempt.</p>
 */
final class EntityReplaySpawnResolver {

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
    static SpawnOutcome attempt(final ServerWorld world, final ChunkPos chunkPosition, final Box searchBox,
                                final Entity entity, final NbtCompound nbt, @Nullable final String operationId) {
        final Entity existing = world.getEntity(entity.getUuid());

        if (ChunkEntityQueries.isMatchingLiveEntity(existing, entity.getType(), chunkPosition) && ChunkEntityQueries.isVisibleFromWorldQuery(world, entity.getUuid(), searchBox)) {
            PayloadWatchTracer.traceRestoreEntitySkipped(world, chunkPosition, entity.getUuidAsString(), operationId,
                    "restore skipped: entity already present in world");
            return SpawnOutcome.ALREADY_PRESENT;
        }

        if (existing != null) {
            PayloadWatchTracer.traceRestoreEntitySkipped(world, chunkPosition, entity.getUuidAsString(), operationId,
                    "restore skipped: duplicate UUID conflict type/chunk mismatch");
            return SpawnOutcome.DUPLICATE_UUID_CONFLICT;
        }

        if (world.spawnEntity(entity)) {
            if (ChunkEntityQueries.isVisibleFromWorldQuery(world, entity.getUuid(), searchBox)) {
                PayloadWatchTracer.traceRestoredEntity(world, chunkPosition, entity, nbt, operationId);
                return SpawnOutcome.SPAWNED;
            }
            PayloadWatchTracer.traceRestoreEntitySkipped(world, chunkPosition, entity.getUuidAsString(), operationId,
                    "restore skipped: spawnEntity returned true but world query did not find entity");
            return SpawnOutcome.SPAWN_ACCEPTED_NOT_VISIBLE;
        }

        PayloadWatchTracer.traceRestoreEntitySkipped(world, chunkPosition, entity.getUuidAsString(), operationId,
                "restore skipped: ServerWorld.spawnEntity returned false");
        return SpawnOutcome.SPAWN_REJECTED;
    }

    /**
     * Outcome of one entity spawn attempt.
     *
     * <p>{@link #ALREADY_PRESENT} intentionally behaves like a retryable
     * non-success so the scheduled queue can clean the stale pending entry on
     * the next tick.</p>
     */
    enum SpawnOutcome {
        ALREADY_PRESENT(ChunkRestorer.ReplayStatus.ALREADY_PRESENT, false, true),
        DUPLICATE_UUID_CONFLICT(ChunkRestorer.ReplayStatus.DUPLICATE_UUID_CONFLICT, false, false),
        SPAWNED(ChunkRestorer.ReplayStatus.SPAWNED, true, false),
        SPAWN_ACCEPTED_NOT_VISIBLE(ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT, false, true),
        SPAWN_REJECTED(ChunkRestorer.ReplayStatus.SPAWN_REJECTED_TRANSIENT, false, true);

        private final ChunkRestorer.ReplayStatus status;
        private final boolean succeeded;
        private final boolean shouldSchedule;

        SpawnOutcome(final ChunkRestorer.ReplayStatus status, final boolean succeeded, final boolean shouldSchedule) {
            this.status = status;
            this.succeeded = succeeded;
            this.shouldSchedule = shouldSchedule;
        }

        ChunkRestorer.ReplayStatus status() {
            return status;
        }

        boolean succeeded() {
            return succeeded;
        }

        boolean shouldSchedule() {
            return shouldSchedule;
        }
    }
}
