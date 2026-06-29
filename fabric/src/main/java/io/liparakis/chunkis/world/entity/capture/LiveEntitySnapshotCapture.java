package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.world.restoration.capture.SnapshotSafetyChecker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Captures a snapshot of live, savable entities in a chunk and writes them into
 * a {@link ChunkDelta}.
 *
 * <p>Capture reads entities from the world's spatial index, serializes them to NBT,
 * and replaces the active-entity set in the delta. Pending (queued) entities that
 * have not yet materialized in the world are preserved across the capture unless
 * they appear in the live snapshot.</p>
 */
public final class LiveEntitySnapshotCapture {

    /**
     * Deterministic ordering for captured entities; used so that
     * {@link ChunkDelta#putEntity} always writes in the same order regardless of
     * the iteration order returned by the world's entity system.
     */
    private static final Comparator<Entity> ENTITY_UUID_COMPARATOR = Comparator.comparing(Entity::getUuid);

    private LiveEntitySnapshotCapture() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures all savable live entities in {@code chunk} and writes them into a
     * {@link ChunkDelta}.
     *
     * <p>If the snapshot appears unsafe (see {@link SnapshotSafetyChecker}) or the
     * live entity list is suspiciously empty while the existing delta has entities,
     * the existing delta is returned unchanged. Otherwise:</p>
     * <ol>
     *   <li>Each live entity is serialized; null or empty results are skipped.</li>
     *   <li>The delta's active-entity set is cleared and refilled in UUID order.</li>
     *   <li>Pending entities that are not yet live in the world are preserved.</li>
     * </ol>
     *
     * @param world         server world containing the chunk
     * @param chunk         target chunk to scan
     * @param existingDelta existing delta for this chunk; may be {@code null}, in
     *                      which case a new delta is created
     * @param operationId   correlation ID for debug tracing; may be {@code null}
     * @param source        caller label used in trace output
     * @return updated delta containing the captured entity snapshot
     */
    public static ChunkDelta<BlockState, NbtCompound> capture(final ServerWorld world, final WorldChunk chunk,
                                                              final ChunkDelta<BlockState, NbtCompound> existingDelta
            , final String operationId, final String source) {
        final ChunkPos chunkPos = chunk.getPos();
        final List<Entity> liveEntities = collectSavableLiveEntities(world, chunk);

        if (shouldSkipEntityCapture(chunk, existingDelta, liveEntities)) {
            PayloadWatchTracer.traceDeltaStage(world.getRegistryKey().getValue().toString(), chunkPos, existingDelta,
                                               operationId, ChunkTraceEventType.WATCH_SKIPPED, "save-entity-capture-unsafe", source,
                                               "preserved "
                                                       + "existing entity payload because live entity state was transient or suspiciously empty"
                    , null);
            return existingDelta;
        }

        final ChunkDelta<BlockState, NbtCompound> delta = existingDelta != null ? existingDelta : new ChunkDelta<>();

        // Serialize live entities in a single pass: clear the active set first so
        // putEntity calls can be interleaved with serialization, eliminating the
        // need for a separate capturedEntities list.
        final List<NbtCompound> capturedNbts = new ArrayList<>(liveEntities.size());
        final Set<String> liveEntityUuids = new HashSet<>();

        delta.clearActiveEntities();
        for (final Entity entity : liveEntities) {
            final NbtCompound nbt = ChunkEntityNbtCapture.serializeEntityNbt(entity);
            if (nbt == null || nbt.isEmpty()) {
                continue;
            }
            delta.putEntity(entity.getId(), nbt);
            capturedNbts.add(nbt);
            final String uuid = ChunkEntityNbtCapture.entityUuid(nbt);
            if (uuid != null) {
                liveEntityUuids.add(uuid);
            }
        }

        delta.clearPendingEntities();
        copyUnresolvedPendingEntities(existingDelta, liveEntityUuids, delta);

        ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.SAVE_TX_START,
                              ChunkTraceSeverity.INFO, ChunkTraceReason.NONE, source,
                              "entity capture scanned live=" + liveEntities.size() + ", serialized="
                                      + capturedNbts.size() + ", " + "retainedPending="
                                      + delta.countPendingEntities(), world.getRegistryKey().getValue().toString(), new DebugChunkKey(chunkPos.x, chunkPos.z), null, null, delta.isDirty(), null);
        PayloadWatchTracer.traceCapturedEntities(world, chunkPos, capturedNbts);

        if (existingDelta != null && delta.countPendingEntities() != 0) {
            PayloadWatchTracer.traceDeltaStage(world.getRegistryKey().getValue().toString(), chunkPos, delta, null,
                                               ChunkTraceEventType.WATCH_CAPTURED, "entity-capture-merged-pending", source,
                                               "live entity " +
                                                       "capture"
                                                       + " preserved unresolved pending entity payloads", null);
        }
        return delta;
    }

    /**
     * Returns the number of savable live entities currently in {@code chunk}.
     *
     * <p>Performs a direct-count iteration without sorting, avoiding the full list
     * allocation that {@link #capture} needs for deterministic ordering.</p>
     *
     * @param world server world containing the chunk
     * @param chunk chunk to inspect
     * @return count of alive, non-player entities whose chunk position matches
     */
    public static int countSavableLiveEntities(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos chunkPos = chunk.getPos();
        final Box searchBox = ChunkEntityQueries.chunkColumnBox(world, chunkPos);
        int count = 0;
        for (final Entity entity : world.getOtherEntities(null, searchBox)) {
            if (entity != null && !(entity instanceof PlayerEntity) && entity.isAlive()
                    && entity.getChunkPos().equals(chunkPos)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns all alive, non-player entities whose chunk position matches
     * {@code chunk}, sorted by UUID for deterministic capture order.
     *
     * <p>The sort is performed every time to ensure that {@link ChunkDelta#putEntity}
     * calls are always issued in the same order, making the resulting delta
     * independent of the world's iteration order.</p>
     *
     * @param world server world to query
     * @param chunk target chunk
     * @return sorted list of savable entities; never {@code null}
     */
    private static List<Entity> collectSavableLiveEntities(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos chunkPos = chunk.getPos();
        final Box searchBox = ChunkEntityQueries.chunkColumnBox(world, chunkPos);
        final List<Entity> liveEntities = new ArrayList<>();
        for (final Entity entity : world.getOtherEntities(null, searchBox)) {
            if (entity == null || entity instanceof PlayerEntity || !entity.isAlive()
                    || !entity.getChunkPos().equals(chunkPos)) {
                continue;
            }
            liveEntities.add(entity);
        }
        liveEntities.sort(ENTITY_UUID_COMPARATOR);
        return liveEntities;
    }

    /**
     * Returns {@code true} when entity capture should be skipped and the existing
     * delta preserved.
     *
     * <p>Two conditions trigger a skip:</p>
     * <ul>
     *   <li>The chunk fails the safety check (e.g. chunk is in a transitional
     *       state where entity data would be unreliable).</li>
     *   <li>The existing delta has known entities but the live scan found none —
     *       this is treated as a suspicious transient empty state that would
     *       otherwise overwrite valid persisted data.</li>
     * </ul>
     *
     * @param chunk         chunk being captured
     * @param existingDelta delta currently held for this chunk; may be {@code null}
     * @param liveEntities  entities returned by the live scan
     * @return {@code true} when capture should be skipped
     */
    private static boolean shouldSkipEntityCapture(final WorldChunk chunk,
                                                   final ChunkDelta<BlockState, NbtCompound> existingDelta,
                                                   final List<Entity> liveEntities) {
        if (SnapshotSafetyChecker.isSnapshotUnsafe(chunk)) {
            return true;
        }
        return existingDelta != null && existingDelta.countNonNullEntities() > 0 && liveEntities.isEmpty();
    }

    /**
     * Copies pending entities from {@code existingDelta} to {@code targetDelta},
     * skipping any whose UUID is already live in the world.
     *
     * <p>A pending entity is considered "unresolved" if its UUID is not present in
     * {@code liveEntityUuids} — meaning it has not yet materialized as a live entity.
     * These are retained so the replay queue can attempt to spawn them later.</p>
     *
     * @param existingDelta   source delta; does nothing when {@code null} or empty
     * @param liveEntityUuids UUIDs of entities that are currently alive in the world
     * @param targetDelta     delta to write unresolved entries into
     */
    private static void copyUnresolvedPendingEntities(final ChunkDelta<BlockState, NbtCompound> existingDelta,
                                                      final Set<String> liveEntityUuids, final ChunkDelta<BlockState,
                    NbtCompound> targetDelta) {
        if (existingDelta == null || existingDelta.countPendingEntities() == 0) {
            return;
        }
        existingDelta.forEachPendingEntity(entityNbt -> {
            if (entityNbt == null) {
                return;
            }
            final String uuid = ChunkEntityNbtCapture.entityUuid(entityNbt);
            if (uuid == null || !liveEntityUuids.contains(uuid)) {
                targetDelta.addPendingEntity(entityNbt);
            }
        });
    }
}