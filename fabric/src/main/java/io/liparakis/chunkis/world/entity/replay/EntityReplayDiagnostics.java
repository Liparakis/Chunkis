package io.liparakis.chunkis.world.entity.replay;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityQueries;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Emits detailed diagnostics for entity replay decisions.
 *
 * <p>This isolates the verbose materialization logging from the replay
 * orchestration path so the coordinator stays focused on replay flow.</p>
 */
final class EntityReplayDiagnostics {

    private EntityReplayDiagnostics() {
        throw new AssertionError("Utility class");
    }

    /**
     * Logs the state observed after a replay decision.
     *
     * @param world        server world
     * @param chunk        target chunk
     * @param runtimeDelta runtime delta at decision time; may be {@code null}
     * @param entityUuid   UUID string being replayed
     * @param entityNbt    entity NBT at decision time; may be {@code null}
     * @param materialized whether the entity was confirmed in the world
     * @param decision     short human-readable branch label
     * @param searchBox    precomputed chunk-column query box
     */
    static void traceReplayDecision(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final String entityUuid,
            @Nullable final NbtCompound entityNbt,
            final boolean materialized,
            final String decision,
            final Box searchBox
    ) {
        final ChunkPos chunkPos = chunk.getPos();
        final UUID uuid = ChunkEntityQueries.parseUuid(entityUuid).orElse(null);
        final Entity liveEntity = uuid == null ? null : world.getEntity(uuid);
        final Identifier nbtType = EntityPayloadNbt.findTypeId(entityNbt).orElse(null);
        final BlockPos nbtBlockPos = EntityPayloadNbt.findBlockPos(entityNbt).orElse(null);
        final boolean chunkLoaded = world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false) != null;
        final boolean entityTicking = nbtBlockPos != null && world.shouldTickEntityAt(nbtBlockPos);
        final ChunkMutationTrackingScope.Cause pendingSuppression =
                PendingChunkMutationSuppression.currentCause(world.getRegistryKey(), chunkPos);
        final ChunkMutationTrackingScope.Cause chunkSuppression =
                chunk instanceof ChunkisMutationGuardDuck guardDuck
                        ? guardDuck.chunkis$getMutationTrackingScope().currentCause()
                        : ChunkMutationTrackingScope.Cause.NONE;
        final boolean visibleByQuery = uuid != null && ChunkEntityQueries.isVisibleFromWorldQuery(world, uuid,
                searchBox);

        final String message = String.format(
                "entity replay materialization: uuid=%s nbtType=%s nbtBlockPos=%s " +
                        "nbtPos=%s liveExists=%b liveType=%s liveChunk=%s chunkLoaded=%b entityTicking=%b " +
                        "mutationSuppression=%s chunkSuppression=%s pendingBefore=%d queueSize=%d materialized=%b " +
                        "visibleByWorldQuery=%b decision=%s thread=%s",
                entityUuid,
                nbtType,
                nbtBlockPos == null ? "<missing>" : nbtBlockPos.toShortString(),
                EntityPayloadNbt.describeRawPos(entityNbt),
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

        io.liparakis.chunkis.debug.trace.ChunkTraceStore.trace(
                io.liparakis.chunkis.debug.model.ChunkisDebugDomain.ENTITY_REPLAY,
                io.liparakis.chunkis.debug.model.ChunkTraceEventType.ENTITY_REPLAY_MATERIALIZATION,
                io.liparakis.chunkis.debug.model.ChunkTraceSeverity.INFO,
                io.liparakis.chunkis.debug.model.ChunkTraceReason.ENTITY_REPLAY,
                "EntityReplayDiagnostics#traceReplayDecision",
                message,
                world.getRegistryKey().getValue().toString(),
                new io.liparakis.chunkis.debug.model.key.DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                null,
                null,
                null
        );

        Chunkis.LOGGER.debug("Chunkis {}", message);
    }
}
