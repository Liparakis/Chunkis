package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.mixin.accessor.ServerEntityManagerAccessor;
import io.liparakis.chunkis.mixin.accessor.ServerWorldAccessor;
import io.liparakis.chunkis.world.restoration.capture.SnapshotSafetyChecker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.entity.SectionedEntityCache;

/**
 * Captures a snapshot of live, savable entities in a chunk and writes them into
 * a {@link ChunkDelta}.
 *
 * <p>Capture reads entities from the chunk's entity sections (hidden ones included), serializes them to NBT,
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

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
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
        return capture(world, chunkPos, liveEntities, SnapshotSafetyChecker.isSnapshotUnsafe(chunk), false,
                existingDelta,
                operationId, source);
    }

    /**
     * Captures an explicit entity list for {@code chunkPos} into a {@link ChunkDelta}.
     *
     * <p>Used when the {@link WorldChunk} may already be gone but its entities are
     * still live, such as vanilla's entity-section save during chunk unload. The list
     * is treated as authoritative: unlike the world-scan overload, an empty list
     * replaces stored entities instead of being preserved as "suspiciously empty".
     * Callers must check snapshot safety themselves when the chunk is loaded.</p>
     *
     * @param world         server world owning the entities
     * @param chunkPos      chunk the entities belong to
     * @param entities      candidate entities; non-savable ones are filtered out
     * @param existingDelta existing delta for this chunk; may be {@code null}
     * @param operationId   correlation ID for debug tracing; may be {@code null}
     * @param source        caller label used in trace output
     * @return updated delta containing the captured entity snapshot
     */
    public static ChunkDelta<BlockState, NbtCompound> capture(final ServerWorld world, final ChunkPos chunkPos,
            final Iterable<? extends Entity> entities, final ChunkDelta<BlockState, NbtCompound> existingDelta,
            final String operationId, final String source) {
        final List<Entity> liveEntities = new ArrayList<>();
        for (final Entity entity : entities) {
            if (isSavableLiveEntity(entity, chunkPos)) {
                liveEntities.add(entity);
            }
        }
        liveEntities.sort(ENTITY_UUID_COMPARATOR);
        return capture(world, chunkPos, liveEntities, false, true, existingDelta, operationId, source);
    }

    /**
     * Shared capture body for both public entry points.
     */
    private static ChunkDelta<BlockState, NbtCompound> capture(final ServerWorld world, final ChunkPos chunkPos,
            final List<Entity> liveEntities, final boolean snapshotUnsafe, final boolean authoritativeList,
            final ChunkDelta<BlockState, NbtCompound> existingDelta, final String operationId, final String source) {
        // An explicit vanilla entity list is authoritative: an empty one means the entities really left.
        if (snapshotUnsafe || (!authoritativeList && isSuspiciouslyEmpty(existingDelta, liveEntities))) {
            PayloadWatchTracer.traceDeltaStage(world.getRegistryKey()
                            .getValue()
                            .toString(),
                    chunkPos,
                    existingDelta,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "save-entity-capture-unsafe",
                    source,
                    "preserved existing entity payload because live entity state was transient or suspiciously empty"
                    ,
                    null);
            return existingDelta;
        }

        final ChunkDelta<BlockState, NbtCompound> delta = existingDelta != null ? existingDelta : new ChunkDelta<>();

        // Serialize first without touching the delta, so an unchanged entity set leaves it clean.
        final List<NbtCompound> capturedNbts = new ArrayList<>(liveEntities.size());
        final List<Integer> capturedIds = new ArrayList<>(liveEntities.size());
        final Set<String> liveEntityUuids = new HashSet<>();
        for (final Entity entity : liveEntities) {
            final NbtCompound nbt = ChunkEntityNbtCapture.serializeEntityNbt(entity);
            if (nbt == null || nbt.isEmpty()) {
                continue;
            }
            capturedNbts.add(nbt);
            capturedIds.add(entity.getId());
            final String uuid = ChunkEntityNbtCapture.entityUuid(nbt);
            if (uuid != null) {
                liveEntityUuids.add(uuid);
            }
        }

        // delta is usually existingDelta itself: copy the pending list before clearing it, or the
        // unresolved (not yet replayed) entity payloads would all be dropped here.
        final List<NbtCompound> previousPending = new ArrayList<>();
        if (existingDelta != null) {
            existingDelta.forEachPendingEntity(previousPending::add);
        }
        final ChunkDelta<BlockState, NbtCompound> keptPending = new ChunkDelta<>();
        copyUnresolvedPendingEntities(world, previousPending, liveEntityUuids, keptPending);
        final List<NbtCompound> pendingToKeep = new ArrayList<>();
        keptPending.forEachPendingEntity(pendingToKeep::add);

        if (existingDelta != null && sameEntities(existingDelta, previousPending, capturedNbts, pendingToKeep)) {
            // Static entities (item frames, armor stands) serialize identically; skip the rewrite.
            return existingDelta;
        }

        delta.clearActiveEntities();
        for (int i = 0; i < capturedNbts.size(); i++) {
            delta.putEntity(capturedIds.get(i), capturedNbts.get(i));
        }
        delta.clearPendingEntities();
        pendingToKeep.forEach(delta::addPendingEntity);

        ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                source,
                "entity capture scanned live=" + liveEntities.size() + ", serialized="
                        + capturedNbts.size() + ", retainedPending="
                        + delta.countPendingEntities(),
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                null,
                delta.isDirty(),
                null);
        PayloadWatchTracer.traceCapturedEntities(world, chunkPos, capturedNbts);

        if (existingDelta != null && delta.countPendingEntities() != 0) {
            PayloadWatchTracer.traceDeltaStage(world.getRegistryKey()
                            .getValue()
                            .toString(), chunkPos, delta, null,
                    ChunkTraceEventType.WATCH_CAPTURED, "entity-capture-merged-pending", source,
                    "live entity capture preserved unresolved pending entity payloads", null);
        }
        return delta;
    }

    /**
     * Fingerprints the payloads {@link #capture} would serialize from an explicit entity list, so
     * the result can be compared with {@link StoredEntityFingerprints}.
     *
     * @param chunkPos chunk the entities are saved for
     * @param entities vanilla's entity list for that chunk
     * @return fingerprint of the savable entity payloads
     */
    public static long[] fingerprintSavable(final ChunkPos chunkPos, final Iterable<? extends Entity> entities) {
        return StoredEntityFingerprints.of(sink -> {
            for (final Entity entity : entities) {
                if (!isSavableLiveEntity(entity, chunkPos)) {
                    continue;
                }
                final NbtCompound nbt = ChunkEntityNbtCapture.serializeEntityNbt(entity);
                if (nbt != null && !nbt.isEmpty()) {
                    sink.accept(nbt);
                }
            }
        });
    }

    /**
     * Returns the number of savable live entities currently in {@code chunk}.
     *
     * <p>Counts without sorting, unlike {@link #capture}, which needs deterministic
     * ordering.</p>
     *
     * @param world server world containing the chunk
     * @param chunk chunk to inspect
     * @return count of alive, non-player entities whose chunk position matches
     */
    public static int countSavableLiveEntities(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos chunkPos = chunk.getPos();
        int count = 0;
        for (final Entity entity : chunkSectionEntities(world, chunkPos)) {
            if (isSavableLiveEntity(entity, chunkPos)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns every entity held in the entity sections of {@code chunkPos}, whatever their tracking status.
     *
     * <p>The world's entity lookup skips sections of a chunk below full level ({@code HIDDEN}), yet those
     * sections keep their entities until vanilla's entity save runs. A chunk saved in that window (its unload
     * save, usually) must still see them, or the chunk stays unowned and its entities are lost in an
     * authoritative world, where vanilla entity files are not written.</p>
     */
    private static List<Entity> chunkSectionEntities(final ServerWorld world, final ChunkPos chunkPos) {
        final SectionedEntityCache<?> cache = ((ServerEntityManagerAccessor) ((ServerWorldAccessor) world)
                .chunkis$getEntityManager()).chunkis$getCache();
        final List<Entity> entities = new ArrayList<>();
        cache.getTrackingSections(chunkPos.toLong())
                .forEach(section -> section.stream()
                        .forEach(entity -> entities.add((Entity) entity)));
        return entities;
    }

    /**
     * Returns {@code true} for alive, non-player entities currently in {@code chunkPos}.
     */
    private static boolean isSavableLiveEntity(final Entity entity, final ChunkPos chunkPos) {
        return entity != null && !(entity instanceof PlayerEntity) && entity.isAlive()
                && entity.getChunkPos()
                .equals(chunkPos);
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
        final List<Entity> liveEntities = new ArrayList<>();
        for (final Entity entity : chunkSectionEntities(world, chunkPos)) {
            if (isSavableLiveEntity(entity, chunkPos)) {
                liveEntities.add(entity);
            }
        }
        liveEntities.sort(ENTITY_UUID_COMPARATOR);
        return liveEntities;
    }

    /**
     * Returns {@code true} when the existing delta has known entities but the live
     * scan found none - a suspicious transient empty state that would otherwise
     * overwrite valid persisted data.
     *
     * @param existingDelta delta currently held for this chunk; may be {@code null}
     * @param liveEntities  entities returned by the live scan
     * @return {@code true} when capture should be skipped
     */
    private static boolean isSuspiciouslyEmpty(final ChunkDelta<BlockState, NbtCompound> existingDelta,
            final List<Entity> liveEntities) {
        return existingDelta != null && existingDelta.countNonNullEntities() > 0 && liveEntities.isEmpty();
    }

    /**
     * Returns whether the delta already holds exactly the captured entities and the
     * pending payloads to keep, so the capture would change nothing.
     *
     * <p>Compares as multisets: active-entity map order follows runtime ids, which
     * carry no meaning for persistence.</p>
     */
    static boolean sameEntities(final ChunkDelta<BlockState, NbtCompound> existing,
            final List<NbtCompound> previousPending, final List<NbtCompound> captured,
            final List<NbtCompound> pendingToKeep) {
        if (!previousPending.equals(pendingToKeep)) {
            return false;
        }
        final java.util.Map<NbtCompound, Integer> counts = new java.util.HashMap<>();
        existing.forEachEntity(nbt -> {
            if (nbt != null) {
                counts.merge(nbt, 1, Integer::sum);
            }
        });
        for (final NbtCompound nbt : pendingToKeep) {
            if (counts.merge(nbt, -1, Integer::sum) < 0) {
                return false;
            }
        }
        for (final NbtCompound nbt : captured) {
            if (counts.merge(nbt, -1, Integer::sum) < 0) {
                return false;
            }
        }
        for (final int remaining : counts.values()) {
            if (remaining != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-adds pending entity payloads to {@code targetDelta} unless they are resolved.
     *
     * <p>A pending payload is resolved once its UUID is live: either captured from this
     * chunk ({@code liveEntityUuids}) or live anywhere in the world (it materialized and
     * walked into another chunk, which now owns it). Keeping a resolved payload would
     * resurrect or duplicate the entity when this chunk reloads. Unresolved payloads are
     * kept so the replay queue can still spawn them.</p>
     *
     * @param world           world used to look entities up by UUID
     * @param pending         pending payloads stored before this capture
     * @param liveEntityUuids UUIDs captured live from this chunk
     * @param targetDelta     delta to write unresolved entries into
     */
    static void copyUnresolvedPendingEntities(final ServerWorld world, final List<NbtCompound> pending,
            final Set<String> liveEntityUuids, final ChunkDelta<BlockState, NbtCompound> targetDelta) {
        for (final NbtCompound entityNbt : pending) {
            if (entityNbt == null) {
                continue;
            }
            final UUID uuid = ChunkEntityNbtCapture.parseEntityUuid(entityNbt);
            final boolean resolved = uuid != null
                    && (liveEntityUuids.contains(uuid.toString()) || (world != null && world.getEntity(uuid) != null));
            if (!resolved) {
                targetDelta.addPendingEntity(entityNbt);
            }
        }
    }
}