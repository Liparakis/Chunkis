package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.entity.capture.StoredEntityFingerprints;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Per-dimension facade for coalesced async CIS saves.
 *
 * <p>The manager owns worker lookup and server-thread snapshot capture. The
 * actual background queue and write lifecycle live in
 * {@link AsyncCisSaveWorker}.</p>
 */
public final class AsyncCisSaveManager {

    /**
     * Log source tag identifier mapping for submit save operations.
     */
    private static final String SUBMIT_SOURCE = "AsyncCisSaveManager#submit";

    /**
     * Backing registry mapping active async workers per dimension world key.
     */
    private static final ConcurrentHashMap<ResourceKey<Level>, AsyncCisSaveWorker> WORKERS =
            new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private AsyncCisSaveManager() {
        throw new AssertionError("Utility class");
    }

    /**
     * Snapshots a live delta and queues it for background persistence, taking its blocks from copied section
     * containers when given ({@code CisSnapshotCapture#captureDeferringBlocks}).
     *
     * @param world          world that owns the chunk
     * @param storage        storage instance used to encode and persist the snapshot
     * @param pos            chunk position being saved
     * @param liveDelta      mutable live delta to snapshot
     * @param deferredBlocks copied block containers by section index, or {@code null} to save the delta's own
     *                       block instructions
     * @param unloading      whether the chunk is unloading (its blocks are then kept for a quick reload)
     * @param operationId    trace correlation id
     */
    public static void submit(
            final ServerLevel world,
            final CisStorage<Block, BlockState, Property<?>, CompoundTag> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, CompoundTag> liveDelta,
            final PalettedContainer<BlockState>[] deferredBlocks,
            final boolean unloading,
            final String operationId
    ) {
        if (!liveDelta.isDirty()) {
            return;
        }
        StoredEntityFingerprints.forget(world, pos);

        final var cisPos = FabricCisStorageHelper.toStoragePos(pos);
        final long generation = liveDelta.getMutationGeneration();
        // Block-entity and entity payloads are only ever replaced in a delta, never changed in place, so the
        // snapshot shares them; the metadata is copied, all but the parts a capture replaces whole.
        final UnaryOperator<CompoundTag> sharePayloads = UnaryOperator.identity();
        final ChunkDeltaView<BlockState, CompoundTag> snapshot;
        if (deferredBlocks != null) {
            snapshot = new DeferredBlocksSnapshotView(
                    liveDelta.snapshotViewWithoutBlocks(sharePayloads, CisNbtUtil::copySharingCapturedState), deferredBlocks,
                    world.getMinY(), liveDelta.getWrittenBlocksPrint(), unloading);
        } else if (liveDelta.isBlockPayloadReleased()) {
            // No chunk to copy the blocks from (it unloaded): they are the stored entry's plus the recorded edits,
            // read on the worker.
            snapshot = new StoredBlocksSnapshotView(
                    liveDelta.snapshotView(sharePayloads, CisNbtUtil::copySharingCapturedState), storage, cisPos);
        } else {
            snapshot = liveDelta.snapshotView(sharePayloads, CisNbtUtil::copySharingCapturedState);
        }

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_QUEUED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SUBMIT_SOURCE,
                "queued async save generation " + generation,
                world.dimension()
                        .identifier()
                        .toString(),
                DebugChunkKeys.of(pos),
                null,
                operationId,
                liveDelta.isDirty(),
                null
        );
        PayloadWatchTracer.traceDeltaStage(
                world.dimension()
                        .identifier()
                        .toString(),
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
                liveDelta.getBlockEditStamp(),
                operationId
        ));
    }

    /**
     * Drains the worker queue for one world and waits for the worker to stop. Called on the server thread, it
     * also applies the completions of the saves just written, which the server thread could not run while it
     * waited, so their deltas are clean for whatever runs next (the close sweep in particular).
     *
     * @param world the world whose worker should be flushed; {@code null} is a no-op
     */
    public static void flushAndClose(final ServerLevel world) {
        if (world == null) {
            return;
        }
        final AsyncCisSaveWorker worker = WORKERS.remove(world.dimension());
        if (worker != null) {
            worker.close();
            if (world.getServer().isSameThread()) {
                worker.applyWrittenCompletions();
            }
        }
    }

    /**
     * Closes all active workers and clears the registry.
     */
    public static void clear() {
        WORKERS.values()
                .forEach(AsyncCisSaveWorker::close);
        WORKERS.clear();
    }

    /**
     * Returns a detached snapshot of queued async saves for diagnostics.
     *
     * @param world world whose queue should be inspected; {@code null} returns an empty map
     * @return queued saves keyed by chunk position
     */
    public static Map<DebugChunkKey, PendingSaveSnapshot> snapshot(final ServerLevel world) {
        if (world == null) {
            return Map.of();
        }
        final AsyncCisSaveWorker worker = WORKERS.get(world.dimension());
        return worker == null ? Map.of() : worker.snapshot();
    }

    /**
     * Resolves worker mapping matching target world registry.
     *
     * @param world target server world
     * @return async worker instance mapping
     */
    private static AsyncCisSaveWorker workerFor(final ServerLevel world) {
        return WORKERS.computeIfAbsent(world.dimension(), ignored -> new AsyncCisSaveWorker(world));
    }

    /**
     * Stable diagnostic view of one queued async save.
     *
     * @param chunkKey    chunk key mapping coordinates
     * @param operationId execution/operation trace ID label
     * @param generation  mutation sequence count value
     * @param dirtyState  true if the delta state matches modifications
     */
    public record PendingSaveSnapshot(
            DebugChunkKey chunkKey,
            String operationId,
            long generation,
            boolean dirtyState
    ) {

    }
}
