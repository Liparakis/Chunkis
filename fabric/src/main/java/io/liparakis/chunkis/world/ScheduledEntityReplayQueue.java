package io.liparakis.chunkis.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ScheduledEntityReplayQueue {
    private static final ConcurrentHashMap<String, ScheduledEntityReplay> ENTITY_REPLAY_QUEUE =
            new ConcurrentHashMap<>();

    private ScheduledEntityReplayQueue() {
    }

    public static void schedule(final ServerWorld world, final ChunkPos chunkPos, final String entityUuid) {
        schedule(world, chunkPos, entityUuid, null);
    }

    public static void schedule(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final String entityUuid,
            final NbtCompound entityNbt
    ) {
        if (world == null || chunkPos == null || entityUuid == null || entityUuid.isBlank()) {
            return;
        }
        final String worldId = world.getRegistryKey().getValue().toString();
        final int before = ENTITY_REPLAY_QUEUE.size();
        ENTITY_REPLAY_QUEUE.put(
                key(worldId, chunkPos, entityUuid),
                new ScheduledEntityReplay(
                        worldId,
                        chunkPos.x,
                        chunkPos.z,
                        entityUuid,
                        entityNbt == null ? null : entityNbt.copy()
                )
        );
        Chunkis.LOGGER.info(
                "Chunkis entity replay scheduled: dimension={} chunk={},{} uuid={} queueBefore={} queueAfter={} thread={}",
                worldId,
                chunkPos.x,
                chunkPos.z,
                entityUuid,
                before,
                ENTITY_REPLAY_QUEUE.size(),
                Thread.currentThread().getName()
        );
    }

    public static void tick(final ServerWorld world) {
        if (world == null || ENTITY_REPLAY_QUEUE.isEmpty()) {
            return;
        }
        final String worldId = world.getRegistryKey().getValue().toString();
        final int queueBefore = ENTITY_REPLAY_QUEUE.size();
        int visited = 0;
        final Iterator<Map.Entry<String, ScheduledEntityReplay>> iterator = ENTITY_REPLAY_QUEUE.entrySet().iterator();
        while (iterator.hasNext()) {
            final ScheduledEntityReplay replay = iterator.next().getValue();
            if (!worldId.equals(replay.worldId)) {
                continue;
            }
            visited++;
            final UUID entityUuid;
            try {
                entityUuid = UUID.fromString(replay.entityUuid);
            } catch (final IllegalArgumentException ignored) {
                traceDrain(world, replay, "INVALID_UUID_RETAINED", queueBefore, ENTITY_REPLAY_QUEUE.size());
                continue;
            }
            final WorldChunk liveChunk = world.getChunkManager().getWorldChunk(replay.chunkX, replay.chunkZ, false);
            final boolean targetChunkLoaded = liveChunk != null;
            if (liveChunk == null) {
                traceDrain(world, replay, "CHUNK_NOT_LOADED_RETRY", queueBefore, ENTITY_REPLAY_QUEUE.size());
                continue;
            }
            final boolean entityTicking = world.shouldTickEntityAt(liveChunk.getPos().getStartPos());
            final ChunkDelta<BlockState, NbtCompound> delta = getChunkDelta(liveChunk);
            if (!(liveChunk instanceof ChunkisDeltaDuck deltaDuck)
                    || (delta == null && replay.entityNbt == null)
                    || (delta != null && delta.countPendingEntities() == 0 && replay.entityNbt == null)) {
                traceDrain(world, replay, "PENDING_PAYLOAD_MISSING_RETRY", queueBefore, ENTITY_REPLAY_QUEUE.size());
                continue;
            }
            final ChunkRestorer.ReplayResult result = ChunkRestorer.replayPendingEntityIfNeeded(
                    world,
                    liveChunk,
                    delta,
                    deltaDuck.chunkis$getRestoreOperationId(),
                    replay.entityUuid,
                    replay.entityNbt
            );
            if (result.status() == ChunkRestorer.ReplayStatus.SPAWNED
                    || result.status() == ChunkRestorer.ReplayStatus.ALREADY_PRESENT) {
                removeMaterializedPendingEntity(liveChunk, replay.entityUuid);
                iterator.remove();
                traceDrain(world, replay, result.status() + "_CONSUMED", queueBefore, ENTITY_REPLAY_QUEUE.size());
                continue;
            }
            traceDrain(
                    world,
                    replay,
                    result.status() + "_RETAINED loaded=" + targetChunkLoaded + " entityTicking=" + entityTicking
                            + " reason=" + result.reason(),
                    queueBefore,
                    ENTITY_REPLAY_QUEUE.size()
            );
        }
        if (visited != 0) {
            Chunkis.LOGGER.info(
                    "Chunkis entity replay drain finished: dimension={} visited={} queueBefore={} queueAfter={} serverThread={} thread={}",
                    worldId,
                    visited,
                    queueBefore,
                    ENTITY_REPLAY_QUEUE.size(),
                    world.getServer() == null || world.getServer().isOnThread(),
                    Thread.currentThread().getName()
            );
        }
    }

    public static void clear() {
        ENTITY_REPLAY_QUEUE.clear();
    }

    public static void acknowledge(final String entityUuid) {
        if (entityUuid == null || entityUuid.isBlank()) {
            return;
        }
        ENTITY_REPLAY_QUEUE.entrySet().removeIf(entry -> entityUuid.equals(entry.getValue().entityUuid));
    }

    public static int size() {
        return ENTITY_REPLAY_QUEUE.size();
    }

    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> getChunkDelta(final Chunk chunk) {
        return chunk instanceof ChunkisDeltaDuck duck
                ? (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta()
                : null;
    }

    private static boolean chunkis$isLiveEntityPresent(final ServerWorld world, final UUID uuid) {
        final net.minecraft.entity.Entity entity = world.getEntity(uuid);
        return entity != null && entity.isAlive() && !entity.isRemoved();
    }

    private static void removeMaterializedPendingEntity(final WorldChunk liveChunk, final String entityUuid) {
        final ChunkDelta<BlockState, NbtCompound> delta = getChunkDelta(liveChunk);
        if (delta == null) {
            return;
        }
        if (!delta.shouldSuppressInitialRepopulation()) {
            return;
        }
        delta.removePendingEntitiesMatching(nbt -> nbt != null
                && nbt.getIntArray("UUID")
                .map(net.minecraft.util.Uuids::toUuid)
                .map(uuid -> uuid.toString().equals(entityUuid))
                .orElse(false));
    }

    private static String key(final String worldId, final ChunkPos chunkPos, final String entityUuid) {
        return worldId + '|' + chunkPos.x + ',' + chunkPos.z + '|' + entityUuid;
    }

    private static void traceDrain(
            final ServerWorld world,
            final ScheduledEntityReplay replay,
            final String decision,
            final int queueBefore,
            final int queueAfter
    ) {
        Chunkis.LOGGER.info(
                "Chunkis entity replay drain: dimension={} chunk={},{} uuid={} decision={} queueBefore={} queueAfter={} serverThread={} thread={}",
                world.getRegistryKey().getValue(),
                replay.chunkX,
                replay.chunkZ,
                replay.entityUuid,
                decision,
                queueBefore,
                queueAfter,
                world.getServer() == null || world.getServer().isOnThread(),
                Thread.currentThread().getName()
        );
    }

    private static final class ScheduledEntityReplay {
        private final String worldId;
        private final int chunkX;
        private final int chunkZ;
        private final String entityUuid;
        private final NbtCompound entityNbt;

        private ScheduledEntityReplay(
                final String worldId,
                final int chunkX,
                final int chunkZ,
                final String entityUuid,
                final NbtCompound entityNbt
        ) {
            this.worldId = worldId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.entityUuid = entityUuid;
            this.entityNbt = entityNbt;
        }
    }
}
