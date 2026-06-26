package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.GlobalChunkTracker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension scheduler for expensive first-time base chunk snapshot capture.
 *
 * <p>Chunkis requires full baseline preservation, but capturing a vanilla-compatible
 * base chunk is the dominant remaining save cost on the server thread. This scheduler
 * bounds that work to {@link #DEFERRED_CAPTURES_PER_TICK} chunks per tick and flushes any
 * remaining queued work synchronously during shutdown.</p>
 *
 * <p>Each dimension owns an independent {@link SchedulerState} so a slow dimension
 * cannot starve captures in another.</p>
 *
 * @author Liparakis
 * @version 1.0
 *
 */
public final class BaseChunkCaptureScheduler {

    private static final String REJECT_SOURCE = "BaseChunkCaptureScheduler#rejectSparse";

    /**
     * Maximum number of deferred base captures processed per server tick.
     *
     * <p>Set {@code -Dchunkis.baseCapture.deferredPerTick=0} or a negative value
     * to disable deferred processing entirely. The legacy
     * {@code chunkis.baseCapture.perTick} property is still honored as a fallback.</p>
     */
    private static final int DEFERRED_CAPTURES_PER_TICK =
            Integer.getInteger(
                    "chunkis.baseCapture.deferredPerTick",
                    Integer.getInteger("chunkis.baseCapture.perTick", 8)
            );

    /**
     * SLF4J template logged after a successful deferred (async) base capture.
     */
    private static final String LOG_CAPTURED =
            "Chunkis [BASE]: Captured deferred base chunk for {} in {}";

    /**
     * SLF4J template logged after a successful synchronous flush at shutdown.
     */
    private static final String LOG_FLUSHED =
            "Chunkis [BASE]: Flushed deferred base chunk for {} in {} during shutdown";

    /**
     * Per-dimension scheduler states, keyed by {@link RegistryKey}.
     *
     * <p>Created lazily on first use and removed on {@link #flushAndClose}.</p>
     */
    private static final ConcurrentHashMap<RegistryKey<World>, SchedulerState> SCHEDULERS =
            new ConcurrentHashMap<>();

    /**
     * Utility class – not instantiable.
     */
    private BaseChunkCaptureScheduler() {
        throw new AssertionError("Utility class");
    }

    /**
     * Processes up to {@link #DEFERRED_CAPTURES_PER_TICK} deferred base captures for
     * {@code world}.  Called once per server tick from the main thread.
     *
     * <p>No-op when {@code world} is {@code null} or {@link #DEFERRED_CAPTURES_PER_TICK}
     * is non-positive.</p>
     *
     * @param world the world whose scheduler should be ticked
     */
    public static void tick(final ServerWorld world) {
        if (world == null || DEFERRED_CAPTURES_PER_TICK <= 0) {
            return;
        }
        stateFor(world).tick(world);
    }

    /**
     * Flushes all queued captures for {@code world} synchronously, then removes
     * its scheduler state.
     *
     * <p>Must be called from the server thread during world unload or shutdown
     * before the underlying storage is closed.</p>
     *
     * @param world the world being closed; {@code null} is a no-op
     */
    public static void flushAndClose(final ServerWorld world) {
        if (world == null) {
            return;
        }
        final SchedulerState state = SCHEDULERS.remove(world.getRegistryKey());
        if (state != null) {
            state.flushAndClose(world);
        }
    }

    /**
     * Discards all queued work across every dimension and clears the scheduler
     * registry.
     *
     * <p><strong>Warning:</strong> drops in-flight work without persisting it.
     * Use only for global teardown or hard-reset paths where storage is also
     * being discarded.</p>
     */
    public static void clear() {
        SCHEDULERS.values().forEach(SchedulerState::clear);
        SCHEDULERS.clear();
    }

    public static Map<DebugChunkKey, QueuedCaptureSnapshot> snapshot(final ServerWorld world) {
        if (world == null) {
            return Map.of();
        }

        final SchedulerState state = SCHEDULERS.get(world.getRegistryKey());
        return state == null ? Map.of() : state.snapshot();
    }

    /**
     * Returns the {@link SchedulerState} for {@code world}, creating one lazily
     * if none exists yet.
     *
     * @param world the world whose dimension key owns the state
     * @return the dimension-local scheduler state
     */
    private static SchedulerState stateFor(final ServerWorld world) {
        return SCHEDULERS.computeIfAbsent(world.getRegistryKey(), k -> new SchedulerState());
    }

    /**
     * Resolves the best available {@link WorldChunk} for a queued position.
     *
     * <p>The queued reference may be stale by the time the scheduler runs. The
     * currently loaded chunk is preferred; the queued reference is the fallback
     * so shutdown flushing can still persist valid data even after the chunk has
     * been unloaded from the cache.</p>
     *
     * @param world       the world being processed
     * @param queuedChunk the originally queued chunk reference
     * @return the active chunk for that position, or {@code queuedChunk} if not loaded
     */
    private static WorldChunk resolveChunk(final ServerWorld world, final WorldChunk queuedChunk) {
        final ChunkPos pos = queuedChunk.getPos();
        final WorldChunk active = world.getChunkManager().getWorldChunk(pos.x, pos.z, false);
        return active != null ? active : queuedChunk;
    }

    /**
     * Extracts the typed Chunkis delta from {@code chunk} via the duck interface.
     *
     * <p>The unchecked cast is unavoidable because the delta type is erased at the
     * {@link ChunkisDeltaDuck} boundary. The cast is safe as long as Chunkis is
     * the only writer of that field.</p>
     *
     * @param chunk the chunk to inspect
     * @return the typed delta, or {@code null} if the chunk does not implement
     * {@link ChunkisDeltaDuck} or carries no delta
     */
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> deltaFrom(final WorldChunk chunk) {
        return chunk instanceof ChunkisDeltaDuck duck
                ? (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta()
                : null;
    }

    /**
     * Validates and submits {@code delta} to {@link AsyncCisSaveManager}.
     *
     * <p>Silently drops the submission if {@link DeltaPersistenceGuard} rejects
     * a sparse delta that has no persisted base.</p>
     *
     * @param world the world that owns the chunk
     * @param pos   the chunk position being saved
     * @param delta the delta to persist
     */
    private static void submitAsync(
            final ServerWorld world,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final String operationId = "save-" + pos.x + '-' + pos.z + '-' + delta.getMutationGeneration();
        if (rejectSparse(
                world,
                pos,
                delta,
                "scheduler-async",
                "BaseChunkCaptureScheduler#submitAsync",
                operationId
        )) {
            return;
        }
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);
        AsyncCisSaveManager.submit(
                world,
                storage,
                pos,
                delta,
                operationId
        );
    }

    /**
     * Validates and saves {@code delta} synchronously on the calling thread.
     *
     * <p>Silently returns {@code false} if {@link DeltaPersistenceGuard} rejects
     * a sparse delta that has no persisted base.</p>
     *
     * @param world the world that owns the chunk
     * @param pos   the chunk position being saved
     * @param delta the delta to persist
     * @return {@code true} if the storage accepted and completed the save
     */
    private static boolean saveSynchronously(
            final ServerWorld world,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final String operationId = "save-" + pos.x + '-' + pos.z + '-' + delta.getMutationGeneration();
        if (rejectSparse(
                world,
                pos,
                delta,
                "scheduler-sync",
                "BaseChunkCaptureScheduler#saveSynchronously",
                operationId
        )) {
            return false;
        }
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);
        if (!storage.save(
                new CisChunkPos(pos.x, pos.z),
                delta,
                operationId
        )) {
            return false;
        }
        GlobalChunkTracker.markSaved(world, pos);
        return true;
    }

    /**
     * Checks the sparse-delta guard and logs a rejection if needed.
     *
     * <p>Extracted to eliminate the identical guard + log pair that existed in
     * both {@link #submitAsync} and {@link #saveSynchronously}.</p>
     *
     * @param world   the world being saved
     * @param pos     the chunk position
     * @param delta   the delta under evaluation
     * @param context short label passed to the persistence guard log
     * @param caller  fully-qualified method name passed to the persistence guard log
     * @return {@code true} if the save should be rejected
     */
    @SuppressWarnings("All")
    private static boolean rejectSparse(
            final ServerWorld world,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String context,
            final String caller,
            final String operationId) {
        if (DeltaPersistenceGuard.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta)) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    caller,
                    "attempted to persist sparse block-entity payload without persisted base chunk NBT on "
                            + context + ": " + DeltaPersistenceGuard.describeDeltaShape(delta),
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(pos.x, pos.z),
                    null,
                    operationId,
                    delta.isDirty(),
                    null
            );
        }
        if (!DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta)) {
            return false;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.SAVE_GUARDS,
                ChunkTraceEventType.SAVE_REJECTED,
                ChunkTraceSeverity.WARN,
                ChunkTraceReason.SPARSE_DELTA_REJECTED,
                REJECT_SOURCE,
                "rejected sparse delta on " + context,
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                operationId,
                delta.isDirty(),
                null
        );
        DeltaPersistenceGuard.logRejectedSparseDeltaWithoutBase(world, pos, delta, context, caller);
        return true;
    }

    /**
     * Emits a debug-level base-capture log only when the logger and chunk-trace
     * filter both permit it, avoiding string formatting overhead on the hot path.
     *
     * @param message the SLF4J message template (two {@code {}} placeholders)
     * @param world   the world being processed
     * @param pos     the chunk position
     */
    private static void debugBaseCapture(
            final String message,
            final ServerWorld world,
            final ChunkPos pos) {
        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug(message, pos, world.getRegistryKey().getValue());
        }
    }

    /**
     * Selects the persistence strategy used after a deferred base capture.
     */
    private enum SaveMode {
        /**
         * Hand the delta off to {@link AsyncCisSaveManager} for background I/O.
         * Used during normal tick processing.
         */
        ASYNC,

        /**
         * Write the delta directly on the calling thread.
         * Used during shutdown / world unload to guarantee data reaches disk
         * before the storage layer closes.
         */
        SYNCHRONOUS
    }

    /**
     * Mutable, dimension-local capture queue.
     *
     * <p>The {@link LinkedHashMap} provides O(1) de-duplication by chunk position
     * (later submissions replace earlier ones for the same key) while preserving
     * insertion order for stable FIFO draining.</p>
     *
     * <p>Queue mutations ({@link #pollNext}, {@link #clear}) are
     * {@code synchronized} on {@code this}.  The expensive work in {@link #process}
     * runs outside the monitor so enqueuers are never blocked by disk or NBT I/O.</p>
     */
    private static final class SchedulerState {

        /**
         * FIFO queue of chunks awaiting base-capture, keyed by packed chunk position
         * ({@link ChunkPos#toLong()}).
         */
        private final LinkedHashMap<Long, WorldChunk> queuedChunks = new LinkedHashMap<>();

        /**
         * Drops every queued chunk from this dimension-local state.
         */
        synchronized void clear() {
            queuedChunks.clear();
        }

        synchronized Map<DebugChunkKey, QueuedCaptureSnapshot> snapshot() {
            final Map<DebugChunkKey, QueuedCaptureSnapshot> snapshots = new LinkedHashMap<>(queuedChunks.size());
            for (final WorldChunk chunk : queuedChunks.values()) {
                final ChunkDelta<BlockState, NbtCompound> delta = deltaFrom(chunk);
                final DebugChunkKey chunkKey = new DebugChunkKey(chunk.getPos().x, chunk.getPos().z);
                snapshots.put(
                        chunkKey, new QueuedCaptureSnapshot(
                                chunkKey,
                                delta != null && delta.isDirty()
                        )
                );
            }
            return snapshots;
        }

        /**
         * Drains the entire queue synchronously.  Called during world unload or
         * server shutdown to ensure queued data reaches disk before storage closes.
         *
         * @param world the world being flushed
         */
        void flushAndClose(final ServerWorld world) {
            WorldChunk chunk;
            while ((chunk = pollNext()) != null) {
                process(world, chunk, SaveMode.SYNCHRONOUS);
            }
        }

        /**
         * Processes up to {@link #DEFERRED_CAPTURES_PER_TICK} queued chunks asynchronously.
         * Called once per server tick.
         *
         * @param world the world being ticked
         */
        void tick(final ServerWorld world) {
            for (int i = 0; i < DEFERRED_CAPTURES_PER_TICK; i++) {
                final WorldChunk chunk = pollNext();
                if (chunk == null) {
                    return;
                }
                process(world, chunk, SaveMode.ASYNC);
            }
        }

        /**
         * Removes and returns the oldest entry in the queue (FIFO head).
         *
         * @return the next chunk awaiting capture, or {@code null} if the queue is empty
         */
        private synchronized WorldChunk pollNext() {
            final Iterator<Map.Entry<Long, WorldChunk>> it = queuedChunks.entrySet().iterator();
            if (!it.hasNext()) {
                return null;
            }
            final WorldChunk chunk = it.next().getValue();
            it.remove();
            return chunk;
        }

        /**
         * Captures missing base chunk data for {@code queuedChunk} and persists
         * the delta using {@code saveMode}.
         *
         * <p>Behaviour differences by mode:</p>
         * <ul>
         *   <li><b>ASYNC</b> – skips the save entirely when the base chunk already
         *       exists; a previous cycle already handled it.</li>
         *   <li><b>SYNCHRONOUS</b> – saves even when the base chunk exists, ensuring
         *       shutdown captures every dirty delta regardless of prior state.</li>
         * </ul>
         *
         * <p>Early-exits without work if the resolved chunk belongs to a different
         * world (dimension mismatch after reassignment) or carries no delta.</p>
         *
         * @param world       the world being processed
         * @param queuedChunk the queued chunk reference (maybe stale)
         * @param saveMode    whether to persist asynchronously or synchronously
         */
        private void process(
                final ServerWorld world,
                final WorldChunk queuedChunk,
                final SaveMode saveMode) {
            final WorldChunk chunk = resolveChunk(world, queuedChunk);
            if (chunk.getWorld() != world) {
                return;
            }

            final ChunkDelta<BlockState, NbtCompound> delta = deltaFrom(chunk);
            if (delta == null) {
                return;
            }

            final boolean hasBase = CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata());

            // ASYNC: if the base already exists another cycle has handled this chunk.
            if (saveMode == SaveMode.ASYNC && hasBase) {
                return;
            }

            if (!hasBase) {
                BaseChunkCaptureUtil.captureBaseChunk(world, chunk, delta);
            }

            final ChunkPos pos = chunk.getPos();

            if (saveMode == SaveMode.ASYNC) {
                submitAsync(world, pos, delta);
                debugBaseCapture(LOG_CAPTURED, world, pos);
            } else if (saveSynchronously(world, pos, delta)) {
                debugBaseCapture(LOG_FLUSHED, world, pos);
            }
        }
    }

    public record QueuedCaptureSnapshot(
            DebugChunkKey chunkKey,
            boolean dirtyState
    ) {
    }
}
