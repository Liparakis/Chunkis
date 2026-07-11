package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.storage.io.region.RegionFile;
import io.liparakis.chunkis.storage.io.region.RegionKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.io.IOException;
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
        try {
            regionFile.compact();
        } catch (final Exception e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to compact CIS region file", e);
        }

        try {
            regionFile.close();
        } catch (final Exception e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to close CIS region file", e);
        }
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
