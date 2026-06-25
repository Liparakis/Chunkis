package io.liparakis.chunkis.debug;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ChunkTraceWatchpoints {

    private static final Object MONITOR = new Object();

    private static final Set<DebugChunkKey> CHUNK_KEYS = new LinkedHashSet<>();
    private static final Set<DebugRegionKey> REGION_KEYS = new LinkedHashSet<>();

    private ChunkTraceWatchpoints() {
        throw new AssertionError("Utility class");
    }

    public static void watchChunk(final DebugChunkKey chunkKey) {
        synchronized (MONITOR) {
            CHUNK_KEYS.add(chunkKey);
        }
    }

    public static void watchRegion(final DebugRegionKey regionKey) {
        synchronized (MONITOR) {
            REGION_KEYS.add(regionKey);
        }
    }

    public static void clear() {
        synchronized (MONITOR) {
            CHUNK_KEYS.clear();
            REGION_KEYS.clear();
        }
    }

    public static boolean isEmpty() {
        synchronized (MONITOR) {
            return CHUNK_KEYS.isEmpty() && REGION_KEYS.isEmpty();
        }
    }

    public static List<DebugChunkKey> watchedChunks() {
        synchronized (MONITOR) {
            return List.copyOf(CHUNK_KEYS);
        }
    }

    public static List<DebugRegionKey> watchedRegions() {
        synchronized (MONITOR) {
            return List.copyOf(REGION_KEYS);
        }
    }

    public static boolean matches(final ChunkTraceEvent event) {
        synchronized (MONITOR) {
            return matchesChunk(event) || matchesRegion(event);
        }
    }

    private static boolean matchesChunk(final ChunkTraceEvent event) {
        return event.chunkKey() != null && CHUNK_KEYS.contains(event.chunkKey());
    }

    private static boolean matchesRegion(final ChunkTraceEvent event) {
        return event.regionKey() != null && REGION_KEYS.contains(event.regionKey());
    }
}
