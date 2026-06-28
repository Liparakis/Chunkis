package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.world.restoration.capture.SnapshotSafetyChecker;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LiveEntitySnapshotCapture {

    private static final Comparator<Entity> ENTITY_UUID_COMPARATOR =
            Comparator.comparing(Entity::getUuid);

    private LiveEntitySnapshotCapture() {
        throw new AssertionError("Utility class");
    }

    public static ChunkDelta<BlockState, NbtCompound> capture(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> existingDelta,
            final String operationId,
            final String source
    ) {
        final ChunkPos chunkPos = chunk.getPos();
        final List<Entity> capturedEntities = new ArrayList<>();
        final List<NbtCompound> serializedEntities = new ArrayList<>();
        final List<Entity> liveEntities = collectSavableLiveEntities(world, chunk);
        if (shouldSkipEntityCapture(chunk, existingDelta, liveEntities)) {
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey().getValue().toString(),
                    chunkPos,
                    existingDelta,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "save-entity-capture-unsafe",
                    source,
                    "preserved existing entity payload because live entity state was transient or suspiciously empty",
                    null
            );
            return existingDelta;
        }

        final Set<String> liveEntityUuids = new HashSet<>();
        for (final Entity entity : liveEntities) {
            final NbtCompound entityNbt = ChunkEntityNbtCapture.serializeEntityNbt(entity);
            if (entityNbt == null || entityNbt.isEmpty()) {
                continue;
            }
            capturedEntities.add(entity);
            serializedEntities.add(entityNbt);
            final String uuid = ChunkEntityNbtCapture.entityUuid(entityNbt);
            if (uuid != null) {
                liveEntityUuids.add(uuid);
            }
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                existingDelta != null ? existingDelta : new ChunkDelta<>();
        delta.clearActiveEntities();
        for (int i = 0; i < capturedEntities.size(); i++) {
            delta.putEntity(capturedEntities.get(i).getId(), serializedEntities.get(i));
        }
        delta.clearPendingEntities();
        copyUnresolvedPendingEntities(existingDelta, liveEntityUuids, delta);
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                source,
                "entity capture scanned live=" + liveEntities.size()
                        + ", serialized=" + serializedEntities.size()
                        + ", retainedPending=" + delta.countPendingEntities(),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                null,
                delta.isDirty(),
                null
        );
        PayloadWatchTracer.traceCapturedEntities(world, chunkPos, serializedEntities);
        if (existingDelta != null && delta.countPendingEntities() != 0) {
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey().getValue().toString(),
                    chunkPos,
                    delta,
                    null,
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "entity-capture-merged-pending",
                    source,
                    "live entity capture preserved unresolved pending entity payloads",
                    null
            );
        }
        return delta;
    }

    public static int countSavableLiveEntities(
            final ServerWorld world,
            final WorldChunk chunk
    ) {
        return collectSavableLiveEntities(world, chunk).size();
    }

    private static List<Entity> collectSavableLiveEntities(
            final ServerWorld world,
            final WorldChunk chunk
    ) {
        final ChunkPos chunkPos = chunk.getPos();
        final List<Entity> liveEntities = new ArrayList<>();
        final Box searchBox = new Box(
                chunkPos.getStartX(),
                world.getBottomY(),
                chunkPos.getStartZ(),
                chunkPos.getEndX() + 1,
                world.getTopYInclusive() + 1,
                chunkPos.getEndZ() + 1
        );
        for (final Entity entity : world.getOtherEntities(null, searchBox)) {
            if (entity == null
                    || entity instanceof PlayerEntity
                    || !entity.isAlive()
                    || !entity.getChunkPos().equals(chunkPos)) {
                continue;
            }
            liveEntities.add(entity);
        }
        liveEntities.sort(ENTITY_UUID_COMPARATOR);
        return liveEntities;
    }

    private static boolean shouldSkipEntityCapture(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> existingDelta,
            final List<Entity> liveEntities
    ) {
        if (SnapshotSafetyChecker.isSnapshotUnsafe(chunk)) {
            return true;
        }
        return existingDelta != null
                && existingDelta.countNonNullEntities() > 0
                && liveEntities.isEmpty();
    }

    private static void copyUnresolvedPendingEntities(
            final ChunkDelta<BlockState, NbtCompound> existingDelta,
            final Set<String> liveEntityUuids,
            final ChunkDelta<BlockState, NbtCompound> targetDelta
    ) {
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

