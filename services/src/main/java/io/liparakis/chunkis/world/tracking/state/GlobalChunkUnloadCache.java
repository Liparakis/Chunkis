package io.liparakis.chunkis.world.tracking.state;

import io.liparakis.chunkis.core.ChunkDelta;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LRU mirror for recently unloaded dirty chunk deltas.
 */
final class GlobalChunkUnloadCache {

    /**
     * Maximum capacity allowed inside the cache layout before eviction triggers.
     */
    private static final int MAX_CACHE_SIZE = 10_000;

    /**
     * Eviction listener callback invoked when items overflow MAX_CACHE_SIZE.
     */
    private final Consumer<DimensionChunkKey> onEvict;

    /**
     * Backing LRU LinkedHashMap containing cache entries.
     */
    private final Map<DimensionChunkKey, ChunkDelta<?, ?>> entries;

    /**
     * Constructor.
     *
     * @param onEvict eviction listener callback
     */
    GlobalChunkUnloadCache(final Consumer<DimensionChunkKey> onEvict) {
        this.onEvict = onEvict;
        this.entries = new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                    final Map.Entry<DimensionChunkKey, ChunkDelta<?, ?>> eldest
            ) {
                final boolean evict = size() > MAX_CACHE_SIZE;
                if (evict) {
                    GlobalChunkUnloadCache.this.onEvict.accept(eldest.getKey());
                }
                return evict;
            }
        };
    }

    /**
     * Puts a delta state mapping inside the LRU cache.
     *
     * @param key   coordinates key mapping
     * @param delta associated block delta
     */
    synchronized void put(final DimensionChunkKey key, final ChunkDelta<?, ?> delta) {
        entries.put(key, delta);
    }

    /**
     * Fetches a cached delta matching key parameters.
     *
     * @param key coordinates key mapping
     * @return cached delta reference or null
     */
    synchronized ChunkDelta<?, ?> get(final DimensionChunkKey key) {
        return entries.get(key);
    }

    /**
     * Removes elements from the cache layout matching keys.
     *
     * @param key coordinates key mapping
     * @return true if elements were removed
     */
    synchronized boolean remove(final DimensionChunkKey key) {
        return entries.remove(key) != null;
    }

    /**
     * Clears all entries from cache structures.
     */
    synchronized void clear() {
        entries.clear();
    }
}
