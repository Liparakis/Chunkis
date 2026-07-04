package io.liparakis.chunkis.world.entity.replay;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Holds entity payloads that could not be spawned during an initial chunk
 * restore and schedules them for retry on subsequent server ticks.
 *
 * <p>Entries are keyed by {@code worldId|chunkX,chunkZ|entityUuid}. Each tick,
 * {@link #tick} drains entries whose target chunk is loaded and attempts to
 * replay them via {@link ChunkRestorer}. Successfully replayed or already-present
 * entities are removed; entries that fail transiently are retained for the next
 * tick.</p>
 *
 * <p><b>Threading:</b> the backing map is a {@link ConcurrentHashMap} so
 * {@link #schedule} and {@link #acknowledge} are safe to call from any thread.
 * {@link #tick} is expected to run on the server thread. The {@code queueBefore}
 * snapshot captured at the start of each tick is used across all trace calls in
 * that tick for consistency; individual {@code queueAfter} values reflect the live
 * map size at the moment of each trace call.</p>
 */
public final class ScheduledEntityReplayQueue {

    /**
     * Map from composite key ({@code worldId|chunkX,chunkZ|entityUuid}) to the
     * scheduled replay entry. {@link ConcurrentHashMap} allows concurrent
     * {@link #schedule}/{@link #acknowledge} calls while {@link #tick} iterates.
     */
    private static final ConcurrentHashMap<String, ScheduledEntityReplay> QUEUE = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ScheduledEntityReplayQueue() {
        throw new AssertionError("Utility class");
    }

    /**
     * Schedules an entity for replay, optionally with a fallback NBT payload.
     *
     * <p>If an entry for the same key already exists it is replaced, making this
     * call idempotent for re-schedules. The NBT is deep-copied so the caller's
     * compound can be safely mutated after this call.</p>
     *
     * @param world      world the entity belongs to; null-checked
     * @param chunkPos   chunk the entity should appear in; null-checked
     * @param entityUuid UUID string of the entity; null/blank-checked
     * @param entityNbt  fallback entity NBT to use if the chunk delta has no
     *                   matching pending entry; may be {@code null}
     */
    public static void schedule(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final String entityUuid,
            final NbtCompound entityNbt
    ) {
        if (world == null || chunkPos == null || entityUuid == null || entityUuid.isBlank()) {
            return;
        }
        final String worldId = world.getRegistryKey()
                .getValue()
                .toString();
        final int before = QUEUE.size();
        QUEUE.put(
                key(worldId, chunkPos, entityUuid),
                new ScheduledEntityReplay(
                        worldId, chunkPos.x, chunkPos.z,
                        entityUuid,
                        entityNbt == null ? null : entityNbt.copy()
                )
        );
        final int after = QUEUE.size();

        // Build the diagnostic message only when at least one consumer will use it.
        // String.format + Thread.currentThread().getName() are non-trivial on hot paths.
        final boolean traceEnabled = io.liparakis.chunkis.debug.config.ChunkisDebugConfig.allows(
                io.liparakis.chunkis.debug.model.ChunkisDebugDomain.ENTITY_REPLAY,
                io.liparakis.chunkis.debug.model.ChunkTraceSeverity.INFO
        );
        final boolean debugLogEnabled = Chunkis.LOGGER.isDebugEnabled();
        if (traceEnabled || debugLogEnabled) {
            final String threadName = Thread.currentThread()
                    .getName();
            final String message = "entity replay scheduled: uuid=" + entityUuid
                    + " queueBefore=" + before
                    + " queueAfter=" + after
                    + " thread=" + threadName;

            if (traceEnabled) {
                io.liparakis.chunkis.debug.trace.ChunkTraceStore.trace(
                        io.liparakis.chunkis.debug.model.ChunkisDebugDomain.ENTITY_REPLAY,
                        io.liparakis.chunkis.debug.model.ChunkTraceEventType.ENTITY_REPLAY_SCHEDULED,
                        io.liparakis.chunkis.debug.model.ChunkTraceSeverity.INFO,
                        io.liparakis.chunkis.debug.model.ChunkTraceReason.ENTITY_REPLAY,
                        "ScheduledEntityReplayQueue#schedule",
                        message,
                        worldId,
                        new io.liparakis.chunkis.debug.model.key.DebugChunkKey(chunkPos.x, chunkPos.z),
                        null,
                        null,
                        null,
                        null
                );
            }

            if (debugLogEnabled) {
                Chunkis.LOGGER.debug(
                        "Chunkis entity replay scheduled uuid={} queueBefore={} queueAfter={} thread={}",
                        entityUuid, before, after, threadName
                );
            }
        }
    }


    /**
     * Drains the queue for {@code world}, attempting to replay each pending entity
     * whose target chunk is currently loaded.
     *
     * <p>Called once per server tick per world. Entries that succeed
     * ({@link ChunkRestorer.ReplayStatus#SPAWNED} or
     * {@link ChunkRestorer.ReplayStatus#ALREADY_PRESENT}) are removed and the
     * corresponding pending entity is cleared from the chunk delta. Entries that
     * fail transiently are retained for the next tick. Entries with permanently
     * malformed UUIDs are removed immediately with a warning, since they can never
     * succeed.</p>
     *
     * @param world the server world whose entries should be drained; null-checked
     */
    public static void tick(final ServerWorld world) {
        if (world == null || QUEUE.isEmpty()) {
            return;
        }
        final String worldId = world.getRegistryKey()
                .getValue()
                .toString();
        final int queueBefore = QUEUE.size();
        int visited = 0;

        final Iterator<Map.Entry<String, ScheduledEntityReplay>> iterator = QUEUE.entrySet()
                .iterator();
        while (iterator.hasNext()) {
            final ScheduledEntityReplay replay = iterator.next()
                    .getValue();
            if (!worldId.equals(replay.worldId)) {
                continue;
            }
            visited++;

            // Malformed UUIDs can never succeed. Remove them immediately rather than
            // retaining them forever, which would cause them to be iterated on every
            // tick indefinitely.
            try {
                UUID.fromString(replay.entityUuid);
            } catch (final IllegalArgumentException ignored) {
                iterator.remove();
                Chunkis.LOGGER.warn(
                        "Chunkis entity replay: removed entry with malformed UUID dimension={} chunk={},{} uuid={}",
                        replay.worldId, replay.chunkX, replay.chunkZ, replay.entityUuid
                );
                continue;
            }

            final WorldChunk liveChunk = world.getChunkManager()
                    .getWorldChunk(replay.chunkX, replay.chunkZ, false);
            if (liveChunk == null) {
                traceDrain(world, replay, "CHUNK_NOT_LOADED_RETRY", queueBefore);
                continue;
            }

            // entityTicking reflects whether the chunk position would tick entities;
            // used only for the diagnostic trace below.
            final boolean entityTicking = world.shouldTickEntityAt(liveChunk.getPos()
                    .getStartPos());

            // Require that the chunk supports the Chunkis delta API, and that at least
            // one payload source (pending delta or fallback NBT) is available.
            if (!(liveChunk instanceof ChunkisDeltaDuck deltaDuck)) {
                traceDrain(world, replay, "PENDING_PAYLOAD_MISSING_RETRY", queueBefore);
                continue;
            }
            final ChunkDelta<BlockState, NbtCompound> delta = getChunkDelta(liveChunk);
            final boolean hasPendingPayload = delta != null && delta.countPendingEntities() > 0;
            final boolean hasFallback = replay.entityNbt != null;
            if (!hasPendingPayload && !hasFallback) {
                traceDrain(world, replay, "PENDING_PAYLOAD_MISSING_RETRY", queueBefore);
                continue;
            }

            final ChunkRestorer.ReplayResult result = ChunkRestorer.replayPendingEntityIfNeeded(
                    world, liveChunk, delta, deltaDuck.chunkis$getRestoreOperationId(),
                    replay.entityUuid, replay.entityNbt
            );

            if (result.status() == ChunkRestorer.ReplayStatus.SPAWNED
                    || result.status() == ChunkRestorer.ReplayStatus.ALREADY_PRESENT) {
                removeMaterializedPendingEntity(liveChunk, replay.entityUuid);
                iterator.remove();
                traceDrain(world, replay, result.status() + "_CONSUMED", queueBefore);
            } else {
                traceDrain(world, replay,
                        result.status() + "_RETAINED entityTicking=" + entityTicking + " reason=" + result.reason(),
                        queueBefore);
            }
        }

        if (visited != 0) {
            final int queueAfter = QUEUE.size();
            final boolean serverThread = world.getServer() == null || world.getServer()
                    .isOnThread();
            final String threadName = Thread.currentThread()
                    .getName();
            final String message = String.format(
                    "entity replay drain finished: visited=%d queueBefore=%d queueAfter=%d serverThread=%b thread=%s",
                    visited, queueBefore, queueAfter, serverThread, threadName
            );

            io.liparakis.chunkis.debug.trace.ChunkTraceStore.trace(
                    io.liparakis.chunkis.debug.model.ChunkisDebugDomain.ENTITY_REPLAY,
                    io.liparakis.chunkis.debug.model.ChunkTraceEventType.ENTITY_REPLAY_DRAIN_FINISHED,
                    io.liparakis.chunkis.debug.model.ChunkTraceSeverity.INFO,
                    io.liparakis.chunkis.debug.model.ChunkTraceReason.ENTITY_REPLAY,
                    "ScheduledEntityReplayQueue#drain",
                    message,
                    worldId,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            Chunkis.LOGGER.debug(
                    "Chunkis entity replay drain finished visited={} queueBefore={} queueAfter={} serverThread={} thread={}",
                    visited,
                    queueBefore,
                    queueAfter,
                    serverThread,
                    threadName
            );
        }
    }

    /**
     * Removes all queue entries matching {@code entityUuid}, regardless of world
     * or chunk.
     *
     * <p>Called when an entity has been confirmed present so its retry entry is no
     * longer needed. This scans the entire map (O(n)) because the composite key
     * includes world and chunk context that is not available at acknowledgement
     * time.</p>
     *
     * @param entityUuid UUID string to remove; null/blank-checked
     */
    public static void acknowledge(final String entityUuid) {
        if (entityUuid == null || entityUuid.isBlank()) {
            return;
        }
        QUEUE.entrySet()
                .removeIf(entry -> entityUuid.equals(entry.getValue().entityUuid));
    }

    /**
     * Clears all pending replay entries across all worlds.
     */
    public static void clear() {
        QUEUE.clear();
    }

    /**
     * Returns the current number of pending replay entries across all worlds.
     *
     * @return queue size
     */
    public static int size() {
        return QUEUE.size();
    }

    /**
     * Returns the {@link ChunkDelta} attached to {@code chunk} via the
     * {@link ChunkisDeltaDuck} mixin, or {@code null} if not available.
     *
     * @param chunk chunk to inspect
     * @return attached delta, or {@code null}
     */
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> getChunkDelta(final Chunk chunk) {
        return chunk instanceof ChunkisDeltaDuck duck
                ? (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta()
                : null;
    }

    /**
     * Removes the pending entity entry matching {@code entityUuid} from the chunk
     * delta, but only when the delta is marked as suppressing initial repopulation.
     *
     * <p>Only suppressed deltas hold "legacy" pending entries that should be cleaned
     * up after materialization. Active deltas retain their pending list for future
     * replays. Malformed UUID arrays in NBT are silently skipped so that one bad
     * entry does not block cleanup of valid entries.</p>
     *
     * @param liveChunk  chunk whose delta is updated
     * @param entityUuid UUID string of the entity whose pending entry should be removed
     */
    private static void removeMaterializedPendingEntity(
            final WorldChunk liveChunk,
            final String entityUuid
    ) {
        final ChunkDelta<BlockState, NbtCompound> delta = getChunkDelta(liveChunk);
        if (delta == null || !delta.shouldSuppressInitialRepopulation()) {
            return;
        }
        delta.removePendingEntitiesMatching(nbt -> EntityPayloadNbt.hasUuid(nbt, entityUuid));
    }

    /**
     * Builds the composite map key for a scheduled replay entry.
     *
     * <p>Format: {@code worldId|chunkX,chunkZ|entityUuid}. The {@code |}
     * separator is safe because Minecraft resource locations use
     * {@code namespace:path} and never contain {@code |}.</p>
     *
     * @param worldId    world registry key string
     * @param chunkPos   chunk coordinates
     * @param entityUuid entity UUID string
     * @return composite key
     */
    private static String key(
            final String worldId,
            final ChunkPos chunkPos,
            final String entityUuid
    ) {
        return worldId + '|' + chunkPos.x + ',' + chunkPos.z + '|' + entityUuid;
    }

    /**
     * Emits a per-entry diagnostic log line during a {@link #tick} drain pass.
     *
     * <p>{@code queueBefore} is the size captured at the start of the tick;
     * {@code queueAfter} is the live size at the moment of this call, reflecting
     * any removes that occurred earlier in the same pass.</p>
     *
     * @param world       server world providing dimension context
     * @param replay      the entry being traced
     * @param decision    short label describing the outcome for this entry
     * @param queueBefore queue size at the start of the current tick
     */
    private static void traceDrain(
            final ServerWorld world,
            final ScheduledEntityReplay replay,
            final String decision,
            final int queueBefore
    ) {
        final int queueAfter = QUEUE.size();
        final boolean serverThread = world.getServer() == null || world.getServer()
                .isOnThread();
        final String threadName = Thread.currentThread()
                .getName();
        final String message = String.format(
                "entity replay drain: chunk=%d,%d uuid=%s decision=%s queueBefore=%d queueAfter=%d serverThread=%b thread=%s",
                replay.chunkX,
                replay.chunkZ,
                replay.entityUuid,
                decision,
                queueBefore,
                queueAfter,
                serverThread,
                threadName
        );

        io.liparakis.chunkis.debug.trace.ChunkTraceStore.trace(
                io.liparakis.chunkis.debug.model.ChunkisDebugDomain.ENTITY_REPLAY,
                io.liparakis.chunkis.debug.model.ChunkTraceEventType.ENTITY_REPLAY_DRAIN,
                io.liparakis.chunkis.debug.model.ChunkTraceSeverity.INFO,
                io.liparakis.chunkis.debug.model.ChunkTraceReason.ENTITY_REPLAY,
                "ScheduledEntityReplayQueue#traceDrain",
                message,
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                new io.liparakis.chunkis.debug.model.key.DebugChunkKey(replay.chunkX, replay.chunkZ),
                null,
                null,
                null,
                null
        );

        Chunkis.LOGGER.debug(
                "Chunkis entity replay drain chunk={},{} uuid={} decision={} queueBefore={} queueAfter={} serverThread={} thread={}",
                replay.chunkX,
                replay.chunkZ,
                replay.entityUuid,
                decision,
                queueBefore,
                queueAfter,
                serverThread,
                threadName
        );
    }

    /**
     * Immutable value type representing one scheduled entity replay entry.
     *
     * @param worldId    registry key string of the target world
     * @param chunkX     X coordinate of the target chunk
     * @param chunkZ     Z coordinate of the target chunk
     * @param entityUuid UUID string of the entity to replay
     * @param entityNbt  fallback entity NBT, or {@code null} if the chunk delta
     *                   should be consulted at retry time
     */
    private record ScheduledEntityReplay(
            String worldId,
            int chunkX,
            int chunkZ,
            String entityUuid,
            NbtCompound entityNbt
    ) {

    }
}
