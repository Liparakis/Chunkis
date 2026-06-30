package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.model.CisConstants;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import java.io.IOException;
import java.util.Map;
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
     * Active thread executing background worker tasks.
     */
    private final Thread thread;

    /**
     * Constructor initializing the worker and starting the daemon thread.
     *
     * @param world target server world
     */
    AsyncCisSaveWorker(final ServerWorld world) {
        this.world = world;
        this.thread = new Thread(this,
                "Chunkis-AsyncSave-" + world.getRegistryKey()
                        .getValue());
        this.thread.setDaemon(true);
        this.thread.start();
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
     * Closes the queue and joins the worker thread.
     */
    void close() {
        queue.close();
        try {
            thread.join();
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
            process(save);
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

            final CisStorage.PreparedSave preparedSave =
                    save.storage()
                            .prepareSave(save.cisPos(), save.snapshot());
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

            save.liveDelta()
                    .setSourceVersion(CisConstants.VERSION);
            GlobalChunkTracker.markSavedIfUnchanged(world, save.pos(), save.liveDelta(), save.generation());
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    save.pos(),
                    save.liveDelta(),
                    save.operationId(),
                    ChunkTraceEventType.WATCH_CAPTURED,
                    save.liveDelta()
                            .isDirty() ? "save-completion-ignored" : "save-completion-applied",
                    PROCESS_SOURCE,
                    save.liveDelta()
                            .isDirty()
                            ? "async save completion did not win live delta race"
                            : "async save completion marked live delta saved",
                    null
            );

        } catch (final IOException e) {
            traceAsyncFailure(save, "async save failed with I/O exception");
            Chunkis.LOGGER.error("Chunkis: Failed async save for chunk {}", save.pos(), e);
        } catch (final Exception e) {
            traceAsyncFailure(save, "async save failed unexpectedly");
            Chunkis.LOGGER.error("Chunkis: Unexpected async save failure for chunk {}", save.pos(), e);
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

    private static void cacheEncodedChunkMetadata(final ChunkDeltaView<BlockState, NbtCompound> snapshot)
            throws IOException {
        if (snapshot.getEncodedChunkMetadata() != null) {
            return;
        }

        final NbtCompound metadata = snapshot.getChunkMetadata();
        if (metadata == null) {
            return;
        }

        snapshot.cacheEncodedChunkMetadata(CisNbtUtil.serializeChunkMetadataForStorage(metadata));
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
         * @param operationId execution/operation ID tag
         */
        PendingSave(
                final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
                final ChunkPos pos,
                final CisChunkPos cisPos,
                final ChunkDelta<BlockState, NbtCompound> liveDelta,
                final ChunkDeltaView<BlockState, NbtCompound> snapshot,
                final long generation,
                final String operationId
        ) {
            this(storage, pos, cisPos, liveDelta, snapshot, generation, operationId, pos.toLong());
        }
    }
}
