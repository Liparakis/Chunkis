package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.storage.io.region.RegionFile;
import io.liparakis.chunkis.storage.io.region.RegionKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LRU cache of open {@link RegionFile} handles for one {@link CisStorage} instance.
 *
 * <p>The cache is keyed by 32x32 region coordinates derived from chunk positions.
 * Cache hits mutate recency order, so both hits and misses take the write lock.
 * Eviction compacts and closes the least-recently-used file before removing it
 * from the cache.</p>
 */
final class RegionFileCache {

    /**
     * Bit shift used to convert chunk coordinates into 32x32 region coordinates.
     */
    private static final int REGION_SHIFT = 5;
    /**
     * Minimum reclaimable bytes before closing a region triggers compaction.
     */
    static final long COMPACT_MIN_SLACK_BYTES = 1L << 20;

    /**
     * Stores storage dir.
     */
    private final Path storageDir;
    /**
     * Stores region key.
     */
    private final Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile> regionCache =
            new Object2ObjectLinkedOpenHashMap<>(CisConstants.MAX_CACHED_REGIONS);
    /**
     * Stores cache lock.
     */
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

    /**
     * Performs region file cache.
     */
    RegionFileCache(final Path storageDir) {
        this.storageDir = storageDir;
    }

    /**
     * Compacts then closes one region file, logging failures without propagating them.
     */
    static void closeRegionFile(final RegionFile regionFile) {
        // Compaction rewrites and re-verifies the whole file; only pay for it when slack is large.
        if (shouldCompactOnClose(regionFile.spaceStats())) {
            try {
                regionFile.compact();
            } catch (final Exception e) {
                Chunkis.LOGGER.warn("Chunkis: Failed to compact CIS region file {}", regionFile.path(), e);
            }
        }

        try {
            regionFile.close();
        } catch (final Exception e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to close CIS region file", e);
        }
    }

    /**
     * Returns whether reclaimable space justifies a full compaction: at least
     * {@value #COMPACT_MIN_SLACK_BYTES} bytes and half of the file.
     *
     * @param stats current space accounting for the region
     * @return {@code true} when compaction is worth its cost
     */
    static boolean shouldCompactOnClose(final RegionFile.RegionSpaceStats stats) {
        final long slack = stats.physicalBytes() - stats.liveBytes();
        return slack >= COMPACT_MIN_SLACK_BYTES && slack * 2 >= stats.physicalBytes();
    }

    /**
     * Converts a chunk position to its owning 32x32 region key.
     */
    static RegionKey regionKey(final CisChunkPos pos) {
        return new RegionKey(pos.x() >> REGION_SHIFT, pos.z() >> REGION_SHIFT);
    }

    /**
     * Returns the cached region file for {@code pos}, opening and caching it when needed.
     *
     * @param pos    chunk position
     * @param create whether to create the region file when it does not exist on disk
     * @return the open region file, or {@code null} when {@code create} is false and
     *         the region file is absent
     * @throws IOException if the region file cannot be opened
     */
    RegionFile get(final CisChunkPos pos, final boolean create) throws IOException {
        final RegionKey key = regionKey(pos);

        cacheLock.writeLock()
                .lock();
        try {
            final RegionFile existing = regionCache.getAndMoveToFirst(key);
            if (existing != null) {
                return existing;
            }

            if (!create && !Files.exists(regionPath(key))) {
                return null;
            }

            if (regionCache.size() >= CisConstants.MAX_CACHED_REGIONS) {
                evictLeastRecentlyUsedRegion();
            }

            final RegionFile newFile = new RegionFile(storageDir, key.x(), key.z());
            regionCache.putAndMoveToFirst(key, newFile);
            return newFile;
        } finally {
            cacheLock.writeLock()
                    .unlock();
        }
    }

    /**
     * Times an operation may meet a region file the cache closed meanwhile before it gives up.
     */
    private static final int EVICTION_RETRIES = 3;

    /**
     * One operation on a region file.
     *
     * @param <T> result type
     */
    @FunctionalInterface
    interface RegionOperation<T> {

        /**
         * Runs the operation.
         *
         * @param regionFile the open region file
         * @return the result
         * @throws IOException on I/O failure
         */
        T apply(RegionFile regionFile) throws IOException;
    }

    /**
     * Runs an operation on the region file holding {@code pos}, under that file's own lock only, so operations
     * on other regions, and their disk syncs, never wait for it.
     *
     * <p>The cache may close (evict) the file between lookup and use. Closing waits for an operation in progress,
     * so an operation that meets a closed file fails before changing anything ({@link RegionFile#close()}); it
     * then runs again on the file reopened.</p>
     *
     * @param pos       chunk position
     * @param create    whether to create a missing region file
     * @param absent    result when the region file does not exist and is not created
     * @param operation the operation
     * @param <T>       result type
     * @return the operation's result, or {@code absent}
     * @throws IOException on I/O failure, or when the file keeps being closed under the operation
     */
    <T> T inRegion(final CisChunkPos pos, final boolean create, final T absent,
            final RegionOperation<T> operation) throws IOException {
        for (int attempt = 0; ; attempt++) {
            final RegionFile regionFile = get(pos, create);
            if (regionFile == null) {
                return absent;
            }
            try {
                return operation.apply(regionFile);
            } catch (final ClosedChannelException evicted) {
                if (attempt >= EVICTION_RETRIES) {
                    throw evicted;
                }
            }
        }
    }

    /**
     * Drains the cache and returns the previously cached files in recency order.
     */
    List<RegionFile> drain() {
        cacheLock.writeLock()
                .lock();
        try {
            final List<RegionFile> filesToClose = new ArrayList<>(regionCache.values());
            regionCache.clear();
            return filesToClose;
        } finally {
            cacheLock.writeLock()
                    .unlock();
        }
    }

    /**
     * Compacts then closes every drained file.
     */
    void closeAll() {
        for (final RegionFile regionFile : drain()) {
            closeRegionFile(regionFile);
        }
    }

    /**
     * Builds the canonical on-disk path for one region key.
     */
    private Path regionPath(final RegionKey key) {
        return storageDir.resolve("r." + key.x() + '.' + key.z() + ".cis");
    }

    /**
     * Evicts the least-recently-used region from the cache.
     *
     * <p>Must be called while holding the write lock.</p>
     */
    private void evictLeastRecentlyUsedRegion() {
        final RegionFile regionFile = regionCache.removeLast();
        if (regionFile != null) {
            closeRegionFile(regionFile);
        }
    }
}
