package io.liparakis.chunkis.world.entity.replay;

import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Holds entity payloads that could not be spawned during an initial chunk
 * restore and schedules them for retry on subsequent server ticks.
 *
 * <p>Entries are keyed by chunk, world and entity UUID, in that order. Each tick,
 * {@link #tick} drains entries whose target chunk is loaded and attempts to
 * replay them via {@link ChunkRestorer}. Successfully replayed or already-present
 * entities are removed; entries that fail transiently are retained for the next
 * tick.</p>
 *
 * <p><b>Threading:</b> the backing map is a {@link ConcurrentSkipListMap} so
 * {@link #schedule} and {@link #acknowledge} are safe to call from any thread.
 * {@link #tick} is expected to run on the server thread. The {@code queueBefore}
 * snapshot captured at the start of each tick is used across all trace calls in
 * that tick for consistency; individual {@code queueAfter} values reflect the live
 * map size at the moment of each trace call.</p>
 */
public final class ScheduledEntityReplayQueue {

    /**
     * How long an entry may wait for its chunk to load before it is dropped; the payload
     * itself stays in the chunk's delta and is rescheduled on the next load.
     */
    private static final long UNLOADED_ENTRY_GRACE_MILLIS = 60_000L;

    /**
     * Map from composite key to the scheduled replay entry, sorted by chunk.
     * {@link ConcurrentSkipListMap} allows concurrent {@link #schedule}/{@link #acknowledge} calls while
     * {@link #tick} iterates.
     */
    private static final ConcurrentSkipListMap<ReplayKey, ScheduledEntityReplay> QUEUE = new ConcurrentSkipListMap<>();

    /**
     * Server-thread time one tick may spend replaying entities before the rest wait for the next tick.
     */
    private static final long DRAIN_BUDGET_NANOS = 2_000_000L;

    /**
     * Next entry for each dimension, so another dimension's tick cannot reset its progress.
     * Accessed only on the server thread.
     */
    private static final EntityReplayProgress<ReplayKey> PROGRESS = new EntityReplayProgress<>();

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
     * <p>An entry for the same key is kept when it already holds this payload, as on the repeated schedules of
     * one reload's several replay passes, and replaced otherwise. The payload is kept, not copied: entity payloads
     * are only ever replaced in a delta, never changed in place, and replay only reads them.</p>
     *
     * @param world      world the entity belongs to; null-checked
     * @param chunkPos   chunk the entity should appear in; null-checked
     * @param entityUuid UUID string of the entity; null/blank-checked
     * @param entityNbt  fallback entity NBT to use if the chunk delta has no
     *                   matching pending entry; may be {@code null}
     */
    public static void schedule(final ServerLevel world,
            final ChunkPos chunkPos,
            final String entityUuid,
            final CompoundTag entityNbt) {
        if (world == null || chunkPos == null || entityUuid == null || entityUuid.isBlank()) {
            return;
        }
        final String worldId = world.dimension()
                .identifier()
                .toString();
        // Build the diagnostic message only for an enabled trace: every entity of a restored chunk is scheduled,
        // and a debug log file (enabled in development) would make each schedule pay the message. The size is a
        // walk of the whole queue.
        final boolean traceEnabled = io.liparakis.chunkis.debug.config.ChunkisDebugConfig.allows(io.liparakis.chunkis.debug.model.ChunkisDebugDomain.ENTITY_REPLAY,
                io.liparakis.chunkis.debug.model.ChunkTraceSeverity.INFO);
        final int before = traceEnabled ? QUEUE.size() : 0;
        QUEUE.compute(new ReplayKey(chunkPos.pack(), worldId, entityUuid),
                (ignoredKey, existing) -> existing != null && existing.entityNbt == entityNbt ? existing
                        : new ScheduledEntityReplay(worldId,
                                chunkPos.x(),
                                chunkPos.z(),
                                entityUuid,
                                entityNbt,
                                System.currentTimeMillis()));
        final int after = traceEnabled ? QUEUE.size() : 0;
        final boolean debugLogEnabled = traceEnabled && Chunkis.LOGGER.isDebugEnabled();
        if (traceEnabled) {
            final String threadName = Thread.currentThread()
                    .getName();
            final String message =
                    "entity replay scheduled: uuid=" + entityUuid + " queueBefore=" + before + " queueAfter=" + after
                            + " thread=" + threadName;

            ChunkTraceStore.trace(ChunkisDebugDomain.ENTITY_REPLAY,
                    ChunkTraceEventType.ENTITY_REPLAY_SCHEDULED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.ENTITY_REPLAY,
                    "ScheduledEntityReplayQueue#schedule",
                    message,
                    worldId,
                    new io.liparakis.chunkis.debug.model.key.DebugChunkKey(chunkPos.x(), chunkPos.z()),
                    null,
                    null,
                    null,
                    null);

            if (debugLogEnabled) {
                Chunkis.LOGGER.debug("Chunkis entity replay scheduled uuid={} queueBefore={} queueAfter={} thread={}",
                        entityUuid,
                        before,
                        after,
                        threadName);
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
    public static void tick(final ServerLevel world) {
        if (world == null || QUEUE.isEmpty()) {
            return;
        }
        final String worldId = world.dimension()
                .identifier()
                .toString();
        final boolean tracing = ChunkisDebugConfig.allows(ChunkisDebugDomain.ENTITY_REPLAY, ChunkTraceSeverity.INFO);
        final int queueBefore = tracing ? QUEUE.size() : 0;
        int visited = 0;

        // Replays cost a tick about 0.1 ms each, and every entity of a batch of reloaded chunks becomes ready in
        // the same tick: past a time budget the rest wait for the next tick, starting where this one stopped.
        // Chunk by chunk, as vanilla adds entities: they tick in the order they were added, and a list in hash
        // order makes every entity miss the server's small chunk cache and leave its neighbours' memory behind.
        final List<Map.Entry<ReplayKey, ScheduledEntityReplay>> entries = new ArrayList<>(QUEUE.entrySet());
        final int size = entries.size();
        final int start = PROGRESS.start(worldId, entries);
        final long deadline = System.nanoTime() + DRAIN_BUDGET_NANOS;
        boolean finished = true;
        for (int step = 0; step < size; step++) {
            final Map.Entry<ReplayKey, ScheduledEntityReplay> entry = entries.get((start + step) % size);
            final ScheduledEntityReplay replay = entry.getValue();
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
                QUEUE.remove(entry.getKey(), replay);
                Chunkis.LOGGER.warn(
                        "Chunkis entity replay: removed entry with malformed UUID dimension={} chunk={},{} uuid={}",
                        replay.worldId,
                        replay.chunkX,
                        replay.chunkZ,
                        replay.entityUuid);
                continue;
            }

            final LevelChunk liveChunk = LoadedChunks.getIfAccessible(world, replay.chunkX, replay.chunkZ);
            if (liveChunk == null) {
                // The payload stays in the chunk's pending list and is rescheduled when the chunk
                // loads again, so an unloaded chunk's entry is dropped instead of being iterated forever.
                // A grace period covers entries queued while the chunk is still being promoted.
                if (System.currentTimeMillis() - replay.scheduledAtMillis() > UNLOADED_ENTRY_GRACE_MILLIS) {
                    QUEUE.remove(entry.getKey(), replay);
                    traceDrain(world, replay, "CHUNK_NOT_LOADED_DROPPED", queueBefore);
                } else if (ChunkisDebugConfig.level() == ChunkisDebugLevel.VERBOSE) {
                    // Repeats every tick until the chunk is accessible; at lower levels it would crowd every
                    // other event out of the trace store.
                    traceDrain(world, replay, "CHUNK_NOT_LOADED_RETRY", queueBefore);
                }
                continue;
            }

            // entityTicking reflects whether the chunk position would tick entities;
            // used only for the diagnostic trace below.
            final boolean entityTicking = world.isPositionEntityTicking(liveChunk.getPos()
                    .getWorldPosition());

            // Require that the chunk supports the Chunkis delta API, and that at least
            // one payload source (pending delta or fallback NBT) is available.
            if (!(liveChunk instanceof ChunkisDeltaDuck deltaDuck)) {
                traceDrain(world, replay, "PENDING_PAYLOAD_MISSING_RETRY", queueBefore);
                continue;
            }
            final ChunkDelta<BlockState, CompoundTag> delta = getChunkDelta(liveChunk);
            final boolean hasPendingPayload = delta != null && delta.countPendingEntities() > 0;
            final boolean hasFallback = replay.entityNbt != null;
            if (!hasPendingPayload && !hasFallback) {
                traceDrain(world, replay, "PENDING_PAYLOAD_MISSING_RETRY", queueBefore);
                continue;
            }

            if (System.nanoTime() > deadline) {
                PROGRESS.pause(worldId, entry.getKey());
                finished = false;
                break;
            }
            final ChunkRestorer.ReplayResult result = ChunkRestorer.replayPendingEntityIfNeeded(world,
                    liveChunk,
                    delta,
                    deltaDuck.chunkis$getRestoreOperationId(),
                    replay.entityUuid,
                    replay.entityNbt);

            if (result.status() == ChunkRestorer.ReplayStatus.SPAWNED
                    || result.status() == ChunkRestorer.ReplayStatus.ALREADY_PRESENT) {
                removeMaterializedPendingEntity(liveChunk, replay.entityUuid);
                QUEUE.remove(entry.getKey(), replay);
                traceDrain(world, replay, result.status() + "_CONSUMED", queueBefore);
            } else {
                traceDrain(world,
                        replay,
                        result.status() + "_RETAINED entityTicking=" + entityTicking + " reason=" + result.reason(),
                        queueBefore);
            }
        }

        if (finished) {
            PROGRESS.complete(worldId);
        }

        if (visited != 0 && tracing) {
            final int queueAfter = QUEUE.size();
            final boolean serverThread = world.getServer()
                    .isSameThread();
            final String threadName = Thread.currentThread()
                    .getName();
            final String message = String.format(
                    "entity replay drain finished: visited=%d queueBefore=%d queueAfter=%d serverThread=%b thread=%s",
                    visited,
                    queueBefore,
                    queueAfter,
                    serverThread,
                    threadName);

            io.liparakis.chunkis.debug.trace.ChunkTraceStore.trace(io.liparakis.chunkis.debug.model.ChunkisDebugDomain.ENTITY_REPLAY,
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
                    null);

            Chunkis.LOGGER.debug(
                    "Chunkis entity replay drain finished visited={} queueBefore={} queueAfter={} serverThread={} thread={}",
                    visited,
                    queueBefore,
                    queueAfter,
                    serverThread,
                    threadName);
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
        PROGRESS.clear();
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
    private static ChunkDelta<BlockState, CompoundTag> getChunkDelta(final ChunkAccess chunk) {
        return chunk instanceof ChunkisDeltaDuck duck ? (ChunkDelta<BlockState, CompoundTag>) duck.chunkis$getDelta()
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
    private static void removeMaterializedPendingEntity(final LevelChunk liveChunk, final String entityUuid) {
        final ChunkDelta<BlockState, CompoundTag> delta = getChunkDelta(liveChunk);
        if (delta == null || !delta.shouldSuppressInitialRepopulation()) {
            return;
        }
        delta.removePendingEntitiesMatching(nbt -> EntityPayloadNbt.hasUuid(nbt, entityUuid));
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
    private static void traceDrain(final ServerLevel world,
            final ScheduledEntityReplay replay,
            final String decision,
            final int queueBefore) {
        if (!ChunkisDebugConfig.allows(ChunkisDebugDomain.ENTITY_REPLAY, ChunkTraceSeverity.INFO)) {
            return;
        }
        final int queueAfter = QUEUE.size();
        final boolean serverThread = world.getServer()
                .isSameThread();
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
                threadName);

        io.liparakis.chunkis.debug.trace.ChunkTraceStore.trace(io.liparakis.chunkis.debug.model.ChunkisDebugDomain.ENTITY_REPLAY,
                io.liparakis.chunkis.debug.model.ChunkTraceEventType.ENTITY_REPLAY_DRAIN,
                io.liparakis.chunkis.debug.model.ChunkTraceSeverity.INFO,
                io.liparakis.chunkis.debug.model.ChunkTraceReason.ENTITY_REPLAY,
                "ScheduledEntityReplayQueue#traceDrain",
                message,
                world.dimension()
                        .identifier()
                        .toString(),
                new io.liparakis.chunkis.debug.model.key.DebugChunkKey(replay.chunkX, replay.chunkZ),
                null,
                null,
                null,
                null);

        Chunkis.LOGGER.debug(
                "Chunkis entity replay drain chunk={},{} uuid={} decision={} queueBefore={} queueAfter={} serverThread={} thread={}",
                replay.chunkX,
                replay.chunkZ,
                replay.entityUuid,
                decision,
                queueBefore,
                queueAfter,
                serverThread,
                threadName);
    }

    /**
     * Queue key: sorts by chunk, so a drain adds entities chunk by chunk.
     *
     * @param chunk      packed chunk position
     * @param worldId    registry key string of the target world
     * @param entityUuid UUID string of the entity to replay
     */
    private record ReplayKey(long chunk, String worldId, String entityUuid) implements Comparable<ReplayKey> {

        @Override
        public int compareTo(final ReplayKey other) {
            final int byChunk = Long.compare(chunk, other.chunk);
            if (byChunk != 0) {
                return byChunk;
            }
            final int byWorld = worldId.compareTo(other.worldId);
            return byWorld != 0 ? byWorld : entityUuid.compareTo(other.entityUuid);
        }
    }

    /**
     * Immutable value type representing one scheduled entity replay entry.
     *
     * @param worldId           registry key string of the target world
     * @param chunkX            X coordinate of the target chunk
     * @param chunkZ            Z coordinate of the target chunk
     * @param entityUuid        UUID string of the entity to replay
     * @param entityNbt         fallback entity NBT, or {@code null} if the chunk delta
     *                          should be consulted at retry time
     * @param scheduledAtMillis wall-clock time the entry was queued
     */
    private record ScheduledEntityReplay(String worldId, int chunkX, int chunkZ, String entityUuid,
                                         CompoundTag entityNbt, long scheduledAtMillis) {

    }
}
