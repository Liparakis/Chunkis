package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.storage.io.CisStorage;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension facade for coalesced async CIS saves.
 *
 * <p>The manager owns worker lookup and server-thread snapshot capture. The
 * actual background queue and write lifecycle live in
 * {@link AsyncCisSaveWorker}.</p>
 */
public final class AsyncCisSaveManager {

    private static final String SUBMIT_SOURCE = "AsyncCisSaveManager#submit";

    private static final ConcurrentHashMap<RegistryKey<World>, AsyncCisSaveWorker> WORKERS =
            new ConcurrentHashMap<>();

    private AsyncCisSaveManager() {
        throw new AssertionError("Utility class");
    }

    /**
     * Snapshots a live delta and queues it for background persistence.
     *
     * @param world world that owns the chunk
     * @param storage storage instance used to encode and persist the snapshot
     * @param pos chunk position being saved
     * @param liveDelta mutable live delta to snapshot
     * @param operationId trace correlation id
     */
    public static void submit(
            final ServerWorld world,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> liveDelta,
            final String operationId
    ) {
        if (!liveDelta.isDirty()) {
            return;
        }

        final var cisPos = FabricCisStorageHelper.toStoragePos(pos);
        final long generation = liveDelta.getMutationGeneration();
        final ChunkDelta<BlockState, NbtCompound> snapshot = liveDelta.snapshot(NbtCompound::copy);

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_QUEUED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SUBMIT_SOURCE,
                "queued async save generation " + generation,
                world.getRegistryKey().getValue().toString(),
                DebugChunkKeys.of(pos),
                null,
                operationId,
                liveDelta.isDirty(),
                null
        );
        PayloadWatchTracer.traceDeltaStage(
                world.getRegistryKey().getValue().toString(),
                pos,
                snapshot,
                operationId,
                ChunkTraceEventType.WATCH_CAPTURED,
                "save-queued",
                SUBMIT_SOURCE,
                "delta queued for async save",
                null
        );

        workerFor(world).submit(new AsyncCisSaveWorker.PendingSave(
                storage,
                pos,
                cisPos,
                liveDelta,
                snapshot,
                generation,
                operationId
        ));
    }

    /**
     * Drains the worker queue for one world and waits for the worker to stop.
     *
     * @param world the world whose worker should be flushed; {@code null} is a no-op
     */
    public static void flushAndClose(final ServerWorld world) {
        if (world == null) {
            return;
        }
        final AsyncCisSaveWorker worker = WORKERS.remove(world.getRegistryKey());
        if (worker != null) {
            worker.close();
        }
    }

    /**
     * Closes all active workers and clears the registry.
     */
    public static void clear() {
        WORKERS.values().forEach(AsyncCisSaveWorker::close);
        WORKERS.clear();
    }

    /**
     * Returns a detached snapshot of queued async saves for diagnostics.
     *
     * @param world world whose queue should be inspected; {@code null} returns an empty map
     * @return queued saves keyed by chunk position
     */
    public static Map<DebugChunkKey, PendingSaveSnapshot> snapshot(final ServerWorld world) {
        if (world == null) {
            return Map.of();
        }
        final AsyncCisSaveWorker worker = WORKERS.get(world.getRegistryKey());
        return worker == null ? Map.of() : worker.snapshot();
    }

    private static AsyncCisSaveWorker workerFor(final ServerWorld world) {
        return WORKERS.computeIfAbsent(world.getRegistryKey(), ignored -> new AsyncCisSaveWorker(world));
    }

    /**
     * Stable diagnostic view of one queued async save.
     */
    public record PendingSaveSnapshot(
            DebugChunkKey chunkKey,
            String operationId,
            long generation,
            boolean dirtyState
    ) {
    }
}
