package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.world.entity.capture.StoredEntityFingerprints;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe helper for managing {@link CisStorage} instances per world dimension.
 *
 * <p>
 * Storage instances are created lazily and cached by {@link RegistryKey}. Concurrent
 * access is handled by a combination of {@link ConcurrentHashMap#compute} (for
 * atomic create-or-replace on the storage map) and a per-wrapper
 * {@link ReadWriteLock} (for safe close while reads are in flight).
 *
 * <p>
 * <b>Lifecycle:</b> Call {@link #getStorage(ServerWorld)} to obtain a storage instance.
 * Call {@link #closeStorage(ServerWorld)} when a world unloads to release resources and
 * prevent memory leaks.
 *
 * <p>
 * <b>Thread safety:</b> All public methods are thread-safe.
 */
public final class FabricCisStorageHelper {

    /**
     * Logger instance reference.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricCisStorageHelper.class);

    /**
     * BlockRegistryAdapter instance mapping block types to mappings.
     */
    private static final BlockRegistryAdapter<Block> REGISTRY_ADAPTER = new FabricBlockRegistryAdapter();

    /**
     * BlockStateAdapter instance mapping block states.
     */
    private static final BlockStateAdapter<Block, BlockState, Property<?>> STATE_ADAPTER = new FabricBlockStateAdapter();

    /**
     * NbtAdapter instance formatting compounds.
     */
    private static final NbtAdapter<NbtCompound> NBT_ADAPTER = new FabricNbtAdapter();

    /**
     * Default fallback block state (Air).
     */
    private static final BlockState DEFAULT_BLOCK_STATE = Blocks.AIR.getDefaultState();

    /**
     * Active storage wrappers keyed by dimension registry key.
     * {@link ConcurrentHashMap#compute} is used for atomic create-or-replace,
     * eliminating the need for an outer lock on the map itself.
     */
    private static final ConcurrentHashMap<RegistryKey<World>, StorageWrapper> storageMap =
            new ConcurrentHashMap<>();

    /**
     * Resolved storage directory paths, cached to avoid repeated filesystem
     * traversal and string concatenation on the hot path.
     */
    private static final ConcurrentHashMap<RegistryKey<World>, Path> pathCache =
            new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private FabricCisStorageHelper() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns the {@link CisStorage} for the given world, creating and caching
     * a new instance if one does not exist or has been closed.
     *
     * <p>
     * Fast path (volatile read on the wrapper's {@code open} flag) is lock-free.
     * The slow path uses {@link ConcurrentHashMap#compute} to atomically create
     * or replace the wrapper, so only one thread ever constructs storage for a
     * given dimension at a time.
     *
     * @param world the server world (must not be null)
     * @return the active {@link CisStorage} for the world's dimension
     * @throws NullPointerException           if world is null
     * @throws StorageInitializationException if storage creation fails
     */
    public static CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage(final ServerWorld world) {
        Objects.requireNonNull(world, "ServerWorld cannot be null");

        final RegistryKey<World> key = world.getRegistryKey();

        // Fast path: wrapper present and open - avoid compute overhead
        final StorageWrapper existing = storageMap.get(key);
        if (existing != null && existing.isOpen()) {
            return existing.getStorage();
        }

        // Slow path: atomic create-or-replace via compute
        return storageMap.compute(key, (k, current) -> {
                    if (current != null && current.isOpen()) {
                        return current;
                    }
                    closeQuietly(current);
                    return openStorageWrapper(world, k);
                })
                .getStorage();
    }

    /**
     * Returns whether CIS storage for {@code world} is currently open, without
     * opening it. Late callers (e.g. vanilla entity saves during world close)
     * use this to avoid reopening storage that shutdown already closed.
     *
     * @param world target server world
     * @return {@code true} if an open storage instance exists
     */
    public static boolean isStorageOpen(final ServerWorld world) {
        final StorageWrapper wrapper = storageMap.get(world.getRegistryKey());
        return wrapper != null && wrapper.isOpen();
    }

    /**
     * Starts a speculative load only when this world's storage is already open.
     * This avoids creating CIS storage for worlds with no Chunkis data.
     *
     * @param world       owning world
     * @param pos         chunk position
     * @param operationId trace correlation id
     */
    public static void prefetch(final ServerWorld world, final ChunkPos pos, final String operationId) {
        Objects.requireNonNull(world, "ServerWorld cannot be null");
        Objects.requireNonNull(pos, "ChunkPos cannot be null");

        final StorageWrapper wrapper = storageMap.get(world.getRegistryKey());
        if (wrapper != null && wrapper.isOpen()) {
            wrapper.prefetch(toStoragePos(pos), operationId);
        }
    }

    /**
     * Closes and removes the storage instance for the given world.
     *
     * <p>
     * Should be called when a world unloads to release file handles and prevent
     * memory leaks. Safe to call multiple times or for worlds without storage.
     *
     * @param world the server world (must not be null)
     * @throws NullPointerException if world is null
     */
    public static void closeStorage(final ServerWorld world) {
        Objects.requireNonNull(world, "ServerWorld cannot be null");

        final RegistryKey<World> key = world.getRegistryKey();
        final StorageWrapper wrapper = storageMap.remove(key);
        pathCache.remove(key);
        RecentChunkBlocks.of(key).clear();

        if (wrapper == null) {
            return;
        }

        try {
            wrapper.close();
            LOGGER.info("Closed Chunkis storage for dimension: {}", key.getValue());
        } catch (final Exception e) {
            LOGGER.error("Error closing Chunkis storage for dimension: {}", key.getValue(), e);
        }
    }

    /**
     * Saves a tracked delta synchronously and clears the tracker entry only when
     * the write succeeds.
     *
     * <p>This is the shared synchronous path used by shutdown/load-path safety
     * sweeps after higher-level callers have already decided the delta is valid
     * to persist.</p>
     *
     * @param world   owning world
     * @param storage target storage
     * @param pos     chunk position
     * @param delta   delta to save
     * @return {@code true} when the payload was written and the tracker entry was cleared
     */
    @SuppressWarnings("UnusedReturnValue")
    public static boolean saveTrackedDelta(
            final ServerWorld world,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        if (delta != null && delta.isBlockPayloadReleased()) {
            return saveWithStoredBlocks(world, storage, pos, delta, ChunkTraceStore.nextOperationId("save"));
        }
        StoredEntityFingerprints.forget(world, pos);
        if (storage.save(toStoragePos(pos), delta)) {
            GlobalChunkTracker.markSaved(world, pos);
            return true;
        }
        return false;
    }

    /**
     * Saves a tracked delta synchronously with an explicit operation id and clears
     * the tracker entry only when the write succeeds.
     *
     * @param world       owning world
     * @param storage     target storage
     * @param pos         chunk position
     * @param delta       delta to save
     * @param operationId trace/storage operation id
     * @return {@code true} when the payload was written and the tracker entry was cleared
     */
    @SuppressWarnings("UnusedReturnValue")
    public static boolean saveTrackedDelta(
            final ServerWorld world,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId) {
        if (delta != null && delta.isBlockPayloadReleased()) {
            return saveWithStoredBlocks(world, storage, pos, delta, operationId);
        }
        StoredEntityFingerprints.forget(world, pos);
        if (storage.save(toStoragePos(pos), delta, operationId)) {
            GlobalChunkTracker.markSaved(world, pos);
            return true;
        }
        return false;
    }

    /**
     * Saves a released delta ({@link ChunkDelta#isBlockPayloadReleased()}) with its blocks read back from the
     * stored entry and its recorded edits applied on top, and clears the tracker entry when the write succeeds. A
     * missing or unreadable entry fails the save and keeps the entry as it is.
     *
     * @param world       owning world
     * @param storage     target storage
     * @param pos         chunk position
     * @param delta       delta to save
     * @param operationId trace/storage operation id
     * @return {@code true} when the payload was written and the tracker entry was cleared
     */
    private static boolean saveWithStoredBlocks(
            final ServerWorld world,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId) {
        StoredEntityFingerprints.forget(world, pos);
        if (!writeDelta(world, storage, pos, delta, operationId)) {
            return false;
        }
        markWritten(world, pos, delta);
        return true;
    }

    /**
     * Saves every delta of a shutdown sweep, encoding and writing them in parallel while the calling thread
     * waits, then marks the written ones saved on the calling thread. Each delta is read by one thread only and
     * nothing changes them meanwhile; storage, block mapping and encoders are safe for concurrent use.
     *
     * <p>Callers apply their persistence guards first: every delta passed is written as it is, a released one
     * with its blocks read back from its stored entry. A failed write keeps that delta dirty and its entry as
     * it is.</p>
     *
     * @param world   owning world
     * @param storage target storage
     * @param deltas  deltas to save, by chunk position
     */
    public static void saveTrackedDeltas(
            final ServerWorld world,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final Map<ChunkPos, ChunkDelta<BlockState, NbtCompound>> deltas) {
        final List<Map.Entry<ChunkPos, ChunkDelta<BlockState, NbtCompound>>> entries = new ArrayList<>(
                deltas.entrySet());
        final boolean[] written = new boolean[entries.size()];
        IntStream.range(0, entries.size())
                .parallel()
                .forEach(i -> written[i] = writeDelta(world, storage, entries.get(i).getKey(),
                        entries.get(i).getValue(), ChunkTraceStore.nextOperationId("save")));
        for (int i = 0; i < written.length; i++) {
            if (written[i]) {
                StoredEntityFingerprints.forget(world, entries.get(i).getKey());
                markWritten(world, entries.get(i).getKey(), entries.get(i).getValue());
            }
        }
    }

    /**
     * Encodes and writes one delta, a released one with its blocks read back from its stored entry and its
     * recorded edits on top. Touches no shared state but the storage, so it may run off the server thread.
     *
     * @param world       owning world, for logging
     * @param storage     target storage
     * @param pos         chunk position
     * @param delta       delta to write
     * @param operationId trace/storage operation id
     * @return {@code true} when the payload was written
     */
    private static boolean writeDelta(
            final ServerWorld world,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId) {
        final CisChunkPos cisPos = toStoragePos(pos);
        try {
            final ChunkDeltaView<BlockState, NbtCompound> payload;
            if (delta.isBlockPayloadReleased()) {
                final StoredBlocksSnapshotView stored = new StoredBlocksSnapshotView(
                        delta.snapshotView(UnaryOperator.identity(), CisNbtUtil::copySharingStoredSections), storage,
                        cisPos);
                stored.loadStoredBlocks();
                payload = stored;
            } else {
                payload = delta;
            }
            return storage.writePrepared(cisPos, storage.prepareSave(cisPos, payload), operationId);
        } catch (final IOException | RuntimeException e) {
            Chunkis.LOGGER.error("Chunkis: Failed to save chunk {} in {}; its stored entry is kept", pos,
                    world.getRegistryKey().getValue(), e);
            return false;
        }
    }

    /**
     * Marks a delta whose payload was just written saved, and drops its tracker entry.
     *
     * @param world owning world
     * @param pos   chunk position
     * @param delta written delta
     */
    private static void markWritten(final ServerWorld world, final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        delta.setSourceVersion(CisConstants.VERSION);
        delta.markSaved();
        GlobalChunkTracker.markSaved(world, pos);
    }

    /**
     * Helper mapping ChunkPos elements to storage coordinate wrappers.
     *
     * @param pos chunk coordinates
     * @return storage coordinate wrapper
     */
    public static CisChunkPos toStoragePos(final ChunkPos pos) {
        return new CisChunkPos(pos.x, pos.z);
    }

    /**
     * Helper mapping X and Z keys to storage coordinate wrappers.
     *
     * @param chunkX chunk X key coordinates
     * @param chunkZ chunk Z key coordinates
     * @return storage coordinate wrapper
     */
    public static CisChunkPos toStoragePos(final int chunkX, final int chunkZ) {
        return new CisChunkPos(chunkX, chunkZ);
    }

    /**
     * Creates a new {@link StorageWrapper} for the given world, logging success.
     * Wraps any {@link Exception} in a {@link StorageInitializationException}.
     *
     * @param world the server world
     * @param key   the dimension registry key (for logging)
     * @return a new open {@link StorageWrapper}
     * @throws StorageInitializationException if the underlying storage cannot be created
     */
    private static StorageWrapper openStorageWrapper(
            final ServerWorld world,
            final RegistryKey<World> key) {
        try {
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = buildStorage(world);
            LOGGER.info("Created Chunkis storage for dimension: {}", key.getValue());
            return new StorageWrapper(storage);
        } catch (final Exception e) {
            LOGGER.error("Failed to create Chunkis storage for dimension: {}", key.getValue(), e);
            throw new StorageInitializationException(
                    "Failed to initialize Chunkis storage for " + key.getValue(), e);
        }
    }

    /**
     * Constructs a fully initialized {@link CisStorage} for the given world.
     *
     * <p>
     * A new {@link PropertyPacker} is created per storage instance because it
     * holds dimension-specific state. All adapter singletons are shared.
     *
     * @param world the server world
     * @return a ready-to-use {@link CisStorage}
     * @throws IOException if directory creation or mapping file initialization fails
     */
    private static CisStorage<Block, BlockState, Property<?>, NbtCompound> buildStorage(
            final ServerWorld world) throws IOException {

        final Path storageDir = resolveAndCreateStorageDir(world);
        final Path mappingFile = ChunkisStoragePaths.computeMappingFile(
                Objects.requireNonNull(world.getServer())
                        .getSavePath(WorldSavePath.ROOT),
                world.getRegistryKey());

        // PropertyPacker is per-storage (holds dimension-specific packed property state)
        final PropertyPacker<Block, BlockState, Property<?>> packer = new PropertyPacker<>(STATE_ADAPTER);

        final CisMapping<Block, BlockState, Property<?>> mapping =
                new CisMapping<>(mappingFile, REGISTRY_ADAPTER, STATE_ADAPTER, packer);

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                new CisStorage<>(storageDir, mapping, NBT_ADAPTER, DEFAULT_BLOCK_STATE);
        storage.setBlockPartSource(RecentChunkBlocks.of(world.getRegistryKey()));
        return storage;
    }

    /**
     * Returns the storage directory for the given world, creating it on disk if
     * it does not exist. The resolved path is cached to avoid repeated filesystem
     * operations on subsequent calls.
     *
     * @param world the server world
     * @return the resolved and created storage directory path
     * @throws IOException if directory creation fails
     */
    private static Path resolveAndCreateStorageDir(final ServerWorld world) throws IOException {
        final RegistryKey<World> key = world.getRegistryKey();

        final Path cached = pathCache.get(key);
        if (cached != null) {
            return cached;
        }

        final Path storageDir = ChunkisStoragePaths.computeRegionsDirectory(
                Objects.requireNonNull(world.getServer())
                        .getSavePath(WorldSavePath.ROOT),
                key);
        Files.createDirectories(storageDir);
        pathCache.put(key, storageDir);
        return storageDir;
    }

    /**
     * Closes the given wrapper, suppressing any exception.
     * Used during atomic replace in {@link #getStorage} to clean up a stale wrapper
     * without aborting the compute lambda.
     *
     * @param wrapper the wrapper to close, may be null
     */
    private static void closeQuietly(final StorageWrapper wrapper) {
        if (wrapper == null) {
            return;
        }
        try {
            wrapper.close();
        } catch (final Exception e) {
            LOGGER.warn("Error closing stale Chunkis storage wrapper", e);
        }
    }

    /**
     * Wraps a {@link CisStorage} with lifecycle state tracking.
     *
     * <p>
     * A {@link ReadWriteLock} allows concurrent {@link #getStorage()} reads while
     * serializing against {@link #close()}, preventing use-after-close on the
     * underlying storage.
     *
     * <p>
     * {@code isOpen()} reads the volatile {@code open} flag without acquiring a
     * lock, providing a fast pre-check before entering the read-locked path.
     */
    private static final class StorageWrapper {

        /**
         * Backing driver storage interface.
         */
        private final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage;

        /**
         * Synchronization lock coordinates wrappers.
         */
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        /**
         * Volatile so that {@link #isOpen()} checks outside the lock see the
         * updated value immediately after {@link #close()} completes.
         */
        private volatile boolean open = true;

        /**
         * Constructor initializing storage wrapper.
         *
         * @param storage underlying driver instance
         */
        StorageWrapper(final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage) {
            this.storage = Objects.requireNonNull(storage, "Storage cannot be null");
        }

        /**
         * Returns the underlying storage under a read lock, preventing concurrent
         * access while a {@link #close()} is in progress.
         *
         * @return the active storage instance
         * @throws IllegalStateException if the storage has been closed
         */
        CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage() {
            lock.readLock()
                    .lock();
            try {
                if (!open) {
                    throw new IllegalStateException("Storage has been closed");
                }
                return storage;
            } finally {
                lock.readLock()
                        .unlock();
            }
        }

        /**
         * Performs prefetch.
         */
        void prefetch(final CisChunkPos pos, final String operationId) {
            lock.readLock()
                    .lock();
            try {
                if (open) {
                    storage.prefetch(pos, operationId);
                }
            } finally {
                lock.readLock()
                        .unlock();
            }
        }

        /**
         * Returns true if the storage is still open.
         * Read is lock-free (volatile) and intended as a fast pre-check only.
         *
         * @return true if open
         */
        boolean isOpen() {
            return open;
        }

        /**
         * Closes the underlying storage under a write lock.
         * Idempotent - subsequent calls after the first are ignored.
         */
        void close() {
            lock.writeLock()
                    .lock();
            try {
                if (!open) {
                    return;
                }
                storage.close();
                open = false;
            } finally {
                lock.writeLock()
                        .unlock();
            }
        }
    }

    /**
     * Thrown when a {@link CisStorage} instance cannot be created for a dimension.
     * Wraps the underlying cause for full stack trace propagation.
     */
    public static final class StorageInitializationException extends RuntimeException {

        /**
         * Constructor.
         *
         * @param message a description identifying the dimension that failed
         * @param cause   the underlying exception
         */
        public StorageInitializationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
