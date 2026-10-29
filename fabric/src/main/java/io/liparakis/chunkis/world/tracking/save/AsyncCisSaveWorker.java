package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.world.entity.capture.StoredEntityFingerprints;
import io.liparakis.chunkis.world.restoration.nbt.CisMetadataCodec;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaSnapshotView;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import io.liparakis.chunkis.world.restoration.capture.SnapshotSafetyChecker;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.mixin.accessor.WorldChunkAccessor;
import net.minecraft.world.chunk.WorldChunk;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;

/**
 * Background worker that drains one world's coalesced async save queue.
 */
final class AsyncCisSaveWorker implements Runnable {

    /**
     * Log source tag identifier mapping for background save execution.
     */
    private static final String PROCESS_SOURCE = "AsyncCisSaveManager$SaveWorker#process";

    /**
     * ServerWorld instance context representing target dimension.
     */
    private final ServerWorld world;

    /**
     * Queue tracking scheduled pending save actions.
     */
    private final PendingSaveQueue queue = new PendingSaveQueue();

    /**
     * Completions of written saves not yet applied on the server thread, by chunk position key; only the latest
     * write of a position counts. Applied by their server task, or by {@link #applyWrittenCompletions()} when
     * the server thread drains the worker while it cannot run tasks.
     */
    private final ConcurrentHashMap<Long, Runnable> unappliedCompletions = new ConcurrentHashMap<>();

    /**
     * Encoding threads. Saves of different chunks encode in parallel (encoder state is per thread and region
     * writes are synchronized); the queue never hands one chunk to two threads at once.
     */
    private static final int THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3));

    /**
     * Active threads executing background worker tasks.
     */
    private final Thread[] threads = new Thread[THREADS];

    /**
     * Constructor initializing the worker and starting its daemon threads.
     *
     * @param world target server world
     */
    AsyncCisSaveWorker(final ServerWorld world) {
        this.world = world;
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(this, "Chunkis-AsyncSave-" + world.getRegistryKey()
                    .getValue() + "-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
    }

    /**
     * Performs cache encoded chunk metadata.
     */
    private static void cacheEncodedChunkMetadata(final ChunkDeltaView<BlockState, NbtCompound> snapshot)
            throws IOException {
        if (snapshot.getEncodedChunkMetadata() != null) {
            return;
        }

        final NbtCompound metadata = snapshot.getChunkMetadata();
        if (metadata == null) {
            return;
        }

        snapshot.cacheEncodedChunkMetadata(CisMetadataCodec.serializeChunkMetadataForStorage(metadata));
    }

    /**
     * Adds a pending save to the queue.
     *
     * @param save save task payload
     */
    void submit(final PendingSave save) {
        queue.submit(save);
    }

    /**
     * Closes the queue and joins the worker threads once they drained it.
     */
    void close() {
        queue.close();
        try {
            for (final Thread worker : threads) {
                worker.join();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread()
                    .interrupt();
            Chunkis.LOGGER.warn(
                    "Chunkis: Interrupted while closing async save worker for {}",
                    world.getRegistryKey()
                            .getValue()
            );
        }
    }

    /**
     * Snapshots the queue status for diagnostic views.
     *
     * @return map of snapshots keyed by coordinates
     */
    Map<DebugChunkKey, AsyncCisSaveManager.PendingSaveSnapshot> snapshot() {
        return queue.snapshot();
    }

    /**
     * Thread entry point draining and processing pending writes.
     */
    @Override
    public void run() {
        while (true) {
            final PendingSave save = queue.poll();
            if (save == null) {
                return;
            }
            try {
                process(save);
            } finally {
                queue.done(save.posKey());
            }
        }
    }

    /**
     * Encodes and writes queued payloads to storage disk space.
     *
     * @param save target pending save task
     */
    @SuppressWarnings("All")
    private void process(final PendingSave save) {
        try {
            if (!GlobalChunkTracker.isCurrentDirtyDelta(
                    world,
                    save.pos(),
                    save.liveDelta(),
                    save.generation())) {
                Chunkis.LOGGER.debug(
                        "[Chunkis/save:{}] Skipping stale async save before encode for {}",
                        save.operationId(),
                        save.cisPos()
                );
                return;
            }
            if (save.snapshot() instanceof StoredBlocksSnapshotView stored) {
                // Before any check reads its blocks; a missing or unreadable entry fails the save here.
                stored.loadStoredBlocks();
            }

            if (DeltaPersistenceGuard.hasInvalidBlockEntityOnlyPayloadWithoutBase(save.snapshot())) {
                if (ChunkisDebugConfig.allows(ChunkisDebugDomain.ASSERTIONS, ChunkTraceSeverity.ERROR)) {
                    ChunkTraceStore.trace(
                            ChunkisDebugDomain.ASSERTIONS,
                            ChunkTraceEventType.ASSERTION_FAILED,
                            ChunkTraceSeverity.ERROR,
                            ChunkTraceReason.INVALID_PAYLOAD,
                            PROCESS_SOURCE,
                            "attempted to persist sparse block-entity payload without persisted base chunk NBT: "
                                    + DeltaPersistenceGuard.describeDeltaShape(save.snapshot()),
                            world.getRegistryKey()
                                    .getValue()
                                    .toString(),
                            new DebugChunkKey(save.pos().x, save.pos().z),
                            null,
                            save.operationId(),
                            save.liveDelta()
                                    .isDirty(),
                            null
                    );
                }
            }
            if (DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(save.snapshot(), true)) {
                DeltaPersistenceGuard.logRejectedSparseDeltaWithoutBase(
                        world,
                        save.pos(),
                        save.snapshot(),
                        "async-worker",
                        PROCESS_SOURCE
                );
                return;
            }

            cacheEncodedChunkMetadata(save.snapshot());

            CisStorage.PreparedSave preparedSave = save.snapshot() instanceof DeferredBlocksSnapshotView deferred
                    ? deferred.prepareReusingStoredBlocks(save.storage(), save.cisPos())
                    : null;
            if (preparedSave == null) {
                preparedSave = save.storage()
                        .prepareSave(save.cisPos(), save.snapshot());
            }
            if (!GlobalChunkTracker.isCurrentDirtyDelta(
                    world,
                    save.pos(),
                    save.liveDelta(),
                    save.generation())) {
                Chunkis.LOGGER.debug(
                        "[Chunkis/save:{}] Skipping stale async save before write for {}",
                        save.operationId(),
                        save.cisPos()
                );
                return;
            }
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    save.pos(),
                    save.snapshot(),
                    save.operationId(),
                    ChunkTraceEventType.WATCH_ENCODED,
                    "encode",
                    PROCESS_SOURCE,
                    "payload encoded",
                    preparedSave.clearChunk() ? 0 : preparedSave.rawData().length
            );
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    save.pos(),
                    save.snapshot(),
                    save.operationId(),
                    ChunkTraceEventType.WATCH_SERIALIZED,
                    "serialize",
                    PROCESS_SOURCE,
                    "payload serialized",
                    preparedSave.clearChunk() ? 0 : preparedSave.rawData().length
            );

            if (!save.storage()
                    .writePrepared(save.cisPos(), preparedSave, save.operationId())) {
                return;
            }
            if (save.snapshot() instanceof DeferredBlocksSnapshotView deferred && !preparedSave.clearChunk()) {
                deferred.recordWritten(preparedSave.rawData());
            }
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    save.pos(),
                    save.snapshot(),
                    save.operationId(),
                    ChunkTraceEventType.WATCH_STORAGE_WRITE,
                    "storage-write",
                    PROCESS_SOURCE,
                    "payload written to storage",
                    null
            );

            // The live delta and tracker are server-thread state: apply completion there, never here.
            // If the server never runs the task (shutdown), the delta stays dirty and the close sweep
            // re-saves it, which is redundant but never lossy.
            completeOnServerThread(save);
        } catch (final IOException e) {
            forgetStoredEntitiesOnServerThread(save);
            traceAsyncFailure(save, "async save failed with I/O exception");
            Chunkis.LOGGER.error("Chunkis: Failed async save for chunk {}", save.pos(), e);
        } catch (final Exception e) {
            forgetStoredEntitiesOnServerThread(save);
            traceAsyncFailure(save, "async save failed unexpectedly");
            Chunkis.LOGGER.error("Chunkis: Unexpected async save failure for chunk {}", save.pos(), e);
        }
    }

    /**
     * Releases the block instructions of a delta whose blocks this save just wrote, when no block changed since
     * the save took them: they are then on disk, and the next save after an edit takes them back from the chunk.
     * A full delta is released only while its chunk is loaded with it, since an unloading chunk's delta may be
     * handed to its reload; a released delta drops its recorded edits, which the entry now holds. Other unsaved
     * changes, such as entities, do not matter: they stay, with the dirty flag.
     *
     * @param save completed save
     */
    private void releaseSavedLoadedBaseline(final PendingSave save) {
        final ChunkDelta<BlockState, NbtCompound> liveDelta = save.liveDelta();
        // During shutdown the completion runs inline on this worker while the server thread waits for it to
        // finish; a chunk lookup from here would wait on the server thread and deadlock the shutdown.
        if (world.getServer() == null || !world.getServer().isOnThread()) {
            return;
        }
        if (!CisNbtUtil.hasFullBlockBaseline(liveDelta.getChunkMetadata())) {
            return;
        }
        if (liveDelta.isBlockPayloadReleased()) {
            liveDelta.releaseBlocksSavedAt(save.blockStamp());
            return;
        }
        final WorldChunk chunk = LoadedChunks.getIfAccessible(world, save.pos().x, save.pos().z);
        // Only a chunk staying in the world: an unloading chunk's delta may be handed to its reload.
        if (chunk instanceof ChunkisDeltaDuck duck && duck.chunkis$getDelta() == liveDelta
                && chunk instanceof WorldChunkAccessor accessor && accessor.chunkis$isLoadedToWorld()
                && !SnapshotSafetyChecker.isSnapshotUnsafe(chunk)) {
            liveDelta.releaseBlocksSavedAt(save.blockStamp());
        }
    }

    /**
     * Marks the live delta saved on the server thread if nothing changed since the snapshot.
     */
    private void completeOnServerThread(final PendingSave save) {
        final var server = world.getServer();
        if (server == null) {
            return;
        }
        // Fingerprint off the server thread; the snapshot is an immutable copy of what was written.
        final ChunkDeltaView<BlockState, NbtCompound> ownSnapshot;
        if (save.snapshot() instanceof DeferredBlocksSnapshotView deferred) {
            ownSnapshot = deferred.rest();
        } else if (save.snapshot() instanceof StoredBlocksSnapshotView stored) {
            ownSnapshot = stored.rest();
        } else {
            ownSnapshot = save.snapshot();
        }
        final long[] entityFingerprint = ownSnapshot instanceof ChunkDeltaSnapshotView<?, ?> snapshotView
                && snapshotView.countPendingEntities() == 0
                ? StoredEntityFingerprints.of(sink -> save.snapshot().forEachEntity(sink))
                : null;
        // The completion waits for the server thread; holding the snapshot (a full copy of the chunk's blocks)
        // until then kept every written chunk's copy alive.
        final PendingSave written = save.withoutSnapshot();
        // What the entry now holds, for the next save from copied sections; anything else written clears it.
        final long[] writtenBlocksPrint = save.snapshot() instanceof DeferredBlocksSnapshotView deferred
                ? deferred.writtenPrint()
                : null;
        // An unloading chunk keeps the blocks just written, so reloading it soon skips decoding them.
        final DeferredBlocksSnapshotView writtenSections = writtenBlocksPrint != null
                ? (DeferredBlocksSnapshotView) save.snapshot()
                : null;
        final Runnable completion = () -> {
            written.liveDelta().setWrittenBlocksPrint(writtenBlocksPrint);
            if (writtenSections != null && writtenSections.unloading()) {
                RecentChunkBlocks.of(world.getRegistryKey()).put(written.cisPos(),
                        new long[]{writtenBlocksPrint[2], writtenBlocksPrint[3]},
                        new long[]{writtenBlocksPrint[0], writtenBlocksPrint[1]}, writtenSections.sections(),
                        writtenSections.bottomY());
            }
            if (entityFingerprint != null) {
                StoredEntityFingerprints.record(world, written.pos(), entityFingerprint);
            }
            if (GlobalChunkTracker.isCurrentDirtyDelta(world, written.pos(), written.liveDelta(), written.generation())) {
                written.liveDelta()
                        .setSourceVersion(CisConstants.VERSION);
            }
            GlobalChunkTracker.markSavedIfUnchanged(world, written.pos(), written.liveDelta(), written.generation());
            releaseSavedLoadedBaseline(written);
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    written.pos(),
                    written.liveDelta(),
                    written.operationId(),
                    ChunkTraceEventType.WATCH_CAPTURED,
                    written.liveDelta()
                            .isDirty() ? "save-completion-ignored" : "save-completion-applied",
                    PROCESS_SOURCE,
                    written.liveDelta()
                            .isDirty()
                            ? "async save completion did not win live delta race"
                            : "async save completion marked live delta saved",
                    null
            );
        };
        unappliedCompletions.put(written.posKey(), completion);
        server.execute(() -> applyCompletion(written.posKey(), completion));
    }

    /**
     * Applies a save's completion unless it was applied already or a later write of the same chunk replaced it.
     *
     * @param posKey     chunk position key
     * @param completion completion to apply
     */
    private void applyCompletion(final long posKey, final Runnable completion) {
        if (unappliedCompletions.remove(posKey, completion)) {
            completion.run();
        }
    }

    /**
     * Applies, on the calling server thread, the completions of every save this worker wrote that the server has
     * not run yet. During shutdown the server thread drains the worker without running its tasks: left
     * unapplied, the written deltas would stay dirty and the close sweep would write them all again.
     */
    void applyWrittenCompletions() {
        unappliedCompletions.forEach(this::applyCompletion);
    }

    /**
     * Drops the chunk's stored-entity fingerprint after a failed write, whose on-disk state is unknown.
     *
     * @param save failed save
     */
    private void forgetStoredEntitiesOnServerThread(final PendingSave save) {
        final var server = world.getServer();
        if (server != null) {
            server.execute(() -> StoredEntityFingerprints.forget(world, save.pos()));
        }
    }

    /**
     * Trace logs async operation failures.
     *
     * @param save    active pending save context
     * @param message failure detail string
     */
    private void traceAsyncFailure(final PendingSave save, final String message) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.REGION_STORAGE,
                ChunkTraceEventType.SAVE_FLUSH_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.IO_EXCEPTION,
                PROCESS_SOURCE,
                message,
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                new DebugChunkKey(save.pos().x, save.pos().z),
                null,
                save.operationId(),
                save.liveDelta()
                        .isDirty(),
                null
        );
    }

    /**
     * Record wrapper storing details representing one queued save operation.
     *
     * @param storage     active storage driver interface
     * @param pos         coordinates pos metadata
     * @param cisPos      storage key translation coordinates
     * @param liveDelta   live delta tracking modifications
     * @param snapshot    snapshot data copied at submit time
     * @param generation  mutation sequence generation code
     * @param blockStamp  block payload stamp of the live delta when the snapshot took its blocks
     * @param operationId execution/operation ID tag
     * @param posKey      coordinate long key mapping
     */
    record PendingSave(
            CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            ChunkPos pos,
            CisChunkPos cisPos,
            ChunkDelta<BlockState, NbtCompound> liveDelta,
            ChunkDeltaView<BlockState, NbtCompound> snapshot,
            long generation,
            long blockStamp,
            String operationId,
            long posKey
    ) {

        /**
         * Constructor resolving key mapping details.
         *
         * @param storage     active storage driver interface
         * @param pos         coordinates pos metadata
         * @param cisPos      storage key translation coordinates
         * @param liveDelta   live delta tracking modifications
         * @param snapshot    snapshot data copied at submit time
         * @param generation  mutation sequence generation code
         * @param blockStamp  block payload stamp of the live delta when the snapshot took its blocks
         * @param operationId execution/operation ID tag
         */
        PendingSave(
                final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
                final ChunkPos pos,
                final CisChunkPos cisPos,
                final ChunkDelta<BlockState, NbtCompound> liveDelta,
                final ChunkDeltaView<BlockState, NbtCompound> snapshot,
                final long generation,
                final long blockStamp,
                final String operationId
        ) {
            this(storage, pos, cisPos, liveDelta, snapshot, generation, blockStamp, operationId, pos.toLong());
        }

        /**
         * Returns this save without its snapshot, for work that outlives the write.
         *
         * @return the same save, snapshot dropped
         */
        PendingSave withoutSnapshot() {
            return new PendingSave(storage, pos, cisPos, liveDelta, null, generation, blockStamp, operationId, posKey);
        }
    }
}
