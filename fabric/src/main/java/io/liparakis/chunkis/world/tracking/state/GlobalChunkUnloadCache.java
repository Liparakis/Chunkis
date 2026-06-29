package io.liparakis.chunkis.world.tracking.state;

import io.liparakis.chunkis.core.ChunkDelta;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LRU mirror for recently unloaded dirty chunk deltas.
 */
final class GlobalChunkUnloadCache {

    private static final int MAX_CACHE_SIZE = 10_000;

    private final Consumer<DimensionChunkKey> onEvict;
    private final Map<DimensionChunkKey, ChunkDelta<?, ?>> entries;

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

    synchronized void put(final DimensionChunkKey key, final ChunkDelta<?, ?> delta) {
        entries.put(key, delta);
    }

    synchronized ChunkDelta<?, ?> get(final DimensionChunkKey key) {
        return entries.get(key);
    }

    synchronized boolean remove(final DimensionChunkKey key) {
        return entries.remove(key) != null;
    }

    synchronized void clear() {
        entries.clear();
    }
}
