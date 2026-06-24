package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.model.CisConstants;
import io.liparakis.chunkis.world.GlobalChunkTracker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension coalescing async write queue for prepared CIS save payloads.
 *
 * <p>Encoding still happens on the server thread because it depends on live delta
 * state and mapping flushes. Compression and region file writes happen on a
 * dedicated daemon worker thread per dimension, removing the dominant synchronous
 * save cost from the main server thread.</p>
 *
 * <p>Coalescing: if the same chunk position is submitted again before the worker
 * drains it, the new {@link PendingSave} replaces the old one in the
 * {@link LinkedHashMap}. The insertion-order iteration ensures FIFO drain order
 * while still de-duplicating redundant writes.</p>
 *
 * <p>Thread-safety: {@link #WORKERS} is a {@link ConcurrentHashMap} accessed only
 * from the server thread (submit / flushAndClose / clear). Each {@link SaveWorker}
 * synchronises internally on its own {@code monitor} object.</p>
 *
 * @author Liparakis
 * @version 1.1
 *
 */
public final class AsyncCisSaveManager {

    private static final String SUBMIT_SOURCE = "AsyncCisSaveManager#submit";
    private static final String PROCESS_SOURCE = "AsyncCisSaveManager$SaveWorker#process";

    /**
     * One worker per registered world dimension, created lazily on first submit.
     */
    private static final ConcurrentHashMap<RegistryKey<World>, SaveWorker> WORKERS =
            new ConcurrentHashMap<>();

    /**
     * Utility class – construction is forbidden.
     */
    private AsyncCisSaveManager() {
        throw new AssertionError("Utility class");
    }

    /**
     * Encodes {@code liveDelta} into a {@link CisStorage.PreparedSave} on the
     * calling (server) thread, then hands it to the dimension's {@link SaveWorker}
     * for asynchronous compression and disk I/O.
     *
     * <p>If the delta is not dirty this method is a no-op. If preparation fails
     * the error is logged and the save is silently dropped – the chunk will be
     * retried on the next dirty-save cycle.</p>
     *
     * @param world     the server world whose CIS storage should be written to
     * @param storage   the CIS storage instance for {@code world}
     * @param pos       the chunk position being saved
     * @param liveDelta the live, mutable delta that tracks changes for {@code pos}
     */
    public static void submit(
            final ServerWorld world,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> liveDelta
    ) {
        submit(world, storage, pos, liveDelta, "save-" + pos.x + '-' + pos.z + '-' + liveDelta.getMutationGeneration());
    }

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

        final CisChunkPos cisPos = new CisChunkPos(pos.x, pos.z);
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
                new DebugChunkKey(pos.x, pos.z),
                null,
                operationId,
                liveDelta.isDirty(),
                null
        );

        workerFor(world).submit(new PendingSave(storage, pos, cisPos, liveDelta, snapshot, generation, operationId));
    }

    /**
     * Drains the worker queue for {@code world} and waits for the worker thread to
     * terminate before returning.  Must be called from the server thread on world
     * unload / server shutdown.
     *
     * @param world the world whose worker should be flushed; {@code null} is a no-op
     */
    public static void flushAndClose(final ServerWorld world) {
        if (world == null) {
            return;
        }
        final SaveWorker worker = WORKERS.remove(world.getRegistryKey());
        if (worker != null) {
            worker.close();
        }
    }

    /**
     * Closes all active workers and clears the registry.  Intended for use during
     * full server shutdown when individual {@link #flushAndClose} calls may have
     * been skipped.
     */
    public static void clear() {
        // Snapshot values before clearing so close() calls cannot race with
        // new entries being inserted by a concurrent submit().
        WORKERS.values().forEach(SaveWorker::close);
        WORKERS.clear();
    }

    /**
     * Returns the {@link SaveWorker} for {@code world}, creating and starting one
     * if none exists yet.
     *
     * @param world the target server world
     * @return the existing or newly created worker
     */
    private static SaveWorker workerFor(final ServerWorld world) {
        return WORKERS.computeIfAbsent(world.getRegistryKey(), k -> new SaveWorker(world));
    }

    /**
     * Daemon thread that drains a dimension-local FIFO queue of {@link PendingSave}
     * entries, coalescing duplicate chunk positions so only the latest save for a
     * given position is written.
     *
     * <p>The {@link LinkedHashMap} provides O(1) coalescing (key replacement) while
     * preserving insertion order for FIFO draining.  All mutations to the map are
     * guarded by {@link #monitor}.</p>
     */
    private static final class SaveWorker implements Runnable {

        private final ServerWorld world;

        /**
         * Lock object for the {@link #pending} map and the {@link #closed} flag.
         * A dedicated object is used instead of {@code this} to keep the monitor
         * scope minimal and to prevent accidental external synchronization.
         */
        private final Object monitor = new Object();

        /**
         * Coalescing FIFO queue.  Key is the packed long chunk position
         * ({@link ChunkPos#toLong()}); value is the latest pending save for that
         * position.  Guarded by {@link #monitor}.
         */
        private final LinkedHashMap<Long, PendingSave> pending = new LinkedHashMap<>();

        /**
         * Set to {@code true} by {@link #close()} to signal the worker to exit.
         */
        private volatile boolean closed;

        private final Thread thread;

        /**
         * Creates and immediately starts the worker thread for {@code world}.
         *
         * @param world the world this worker persists data for
         */
        private SaveWorker(final ServerWorld world) {
            this.world = world;
            this.thread = new Thread(this, "Chunkis-AsyncSave-" + world.getRegistryKey().getValue());
            this.thread.setDaemon(true);
            this.thread.start();
        }

        /**
         * Enqueues a save payload, coalescing with any existing entry for the same
         * chunk position.  Drops the payload silently if the worker is already closed.
         *
         * @param save the prepared save to enqueue
         */
        void submit(final PendingSave save) {
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                // LinkedHashMap.put replaces the value but preserves the existing
                // key's insertion order, so we remove first to promote the entry to
                // the tail (most-recent position), keeping drain order meaningful.
                pending.remove(save.posKey());
                pending.put(save.posKey(), save);
                monitor.notify(); // wake worker; only one thread waits
            }
        }

        /**
         * Signals the worker to stop and blocks until its thread terminates,
         * ensuring all already-queued saves are drained before returning.
         */
        void close() {
            synchronized (monitor) {
                closed = true;
                monitor.notifyAll();
            }
            try {
                thread.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                Chunkis.LOGGER.warn(
                        "Chunkis: Interrupted while closing async save worker for {}",
                        world.getRegistryKey().getValue()
                );
            }
        }

        /**
         * Worker loop: waits for entries, drains them one at a time.
         * Exits when {@link #closed} is {@code true} and the queue is empty,
         * ensuring in-flight saves complete before the thread dies.
         */
        @Override
        public void run() {
            while (true) {
                final PendingSave save = poll();
                if (save == null) {
                    return; // closed and empty – clean exit
                }
                process(save);
            }
        }

        /**
         * Blocks until a save is available or the worker is closed with an empty
         * queue.
         *
         * @return the next save to process, or {@code null} if the worker should exit
         */
        private PendingSave poll() {
            synchronized (monitor) {
                while (pending.isEmpty()) {
                    if (closed) {
                        return null;
                    }
                    try {
                        monitor.wait();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                final Map.Entry<Long, PendingSave> head = pending.entrySet().iterator().next();
                pending.remove(head.getKey());
                return head.getValue();
            }
        }

        /**
         * Performs the actual compression and disk write for one {@link PendingSave}.
         *
         * <p>A save is silently skipped if:</p>
         * <ul>
         *   <li>the live delta's mutation generation has advanced beyond the one
         *       captured at prepare-time (a newer async save will follow), or</li>
         *   <li>{@link DeltaPersistenceGuard} rejects it as a sparse delta without
         *       a base snapshot.</li>
         * </ul>
         *
         * <p>On success the live delta's source version is updated and
         * {@link GlobalChunkTracker} is notified so the chunk is not re-saved
         * unnecessarily.</p>
         *
         * @param save the payload to write
         */
        @SuppressWarnings("All")
        private void process(final PendingSave save) {
            try {
                //Stale generation: a newer write will supersede this one.
                if (save.liveDelta().getMutationGeneration() != save.generation()) {
                    return;
                }

                //Sparse delta without a persisted base is unsafe to write.
                if (DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(save.snapshot())) {
                    DeltaPersistenceGuard.logRejectedSparseDeltaWithoutBase(
                            world,
                            save.pos(),
                            save.snapshot(),
                            "async-worker",
                            "AsyncCisSaveManager$SaveWorker#process"
                    );
                    return;
                }

                //Encode on the background thread!
                final CisStorage.PreparedSave preparedSave = save.storage().prepareSave(save.cisPos(), save.snapshot());

                if (!save.storage().writePrepared(save.cisPos(), preparedSave, save.operationId())) {
                    return;
                }

                save.liveDelta().setSourceVersion(CisConstants.VERSION);
                GlobalChunkTracker.markSavedIfUnchanged(world, save.pos(), save.liveDelta(), save.generation());

            } catch (final IOException e) {
                traceAsyncFailure(save, "async save failed with I/O exception");
                Chunkis.LOGGER.error("Chunkis: Failed async save for chunk {}", save.pos(), e);
            } catch (final Exception e) {
                traceAsyncFailure(save, "async save failed unexpectedly");
                Chunkis.LOGGER.error("Chunkis: Unexpected async save failure for chunk {}", save.pos(), e);
            }
        }

        private void traceAsyncFailure(final PendingSave save, final String message) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.REGION_STORAGE,
                    ChunkTraceEventType.SAVE_FLUSH_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.IO_EXCEPTION,
                    PROCESS_SOURCE,
                    message,
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(save.pos().x, save.pos().z),
                    null,
                    save.operationId(),
                    save.liveDelta().isDirty(),
                    null
            );
        }
    }

    /**
     * Immutable snapshot of everything needed to write one chunk asynchronously.
     *
     * <p>{@link #posKey()} is pre-computed once at construction so the hot path
     * inside {@link SaveWorker#submit} and {@link SaveWorker#poll} avoids repeated
     * {@link ChunkPos#toLong()} calls.</p>
     */
    private record PendingSave(
            CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            ChunkPos pos,
            CisChunkPos cisPos,
            ChunkDelta<BlockState, NbtCompound> liveDelta,
            ChunkDelta<BlockState, NbtCompound> snapshot,
            long generation,
            String operationId,
            long posKey
    ) {
        /**
         * Canonical constructor – derives {@link #posKey} from {@code pos}.
         */
        PendingSave(
                final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
                final ChunkPos pos,
                final CisChunkPos cisPos,
                final ChunkDelta<BlockState, NbtCompound> liveDelta,
                final ChunkDelta<BlockState, NbtCompound> snapshot,
                final long generation,
                final String operationId
        ) {
            this(storage, pos, cisPos, liveDelta, snapshot, generation, operationId, pos.toLong());
        }
    }
}
