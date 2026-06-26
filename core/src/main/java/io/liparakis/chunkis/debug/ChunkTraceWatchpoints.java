package io.liparakis.chunkis.debug;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ChunkTraceWatchpoints {

    private static final Object MONITOR = new Object();

    private static final Set<DebugChunkKey> CHUNK_KEYS = new LinkedHashSet<>();
    private static final Set<DebugRegionKey> REGION_KEYS = new LinkedHashSet<>();
    private static final Set<PayloadWatchTarget> PAYLOAD_TARGETS = new LinkedHashSet<>();

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
            PAYLOAD_TARGETS.clear();
        }
    }

    public static boolean isEmpty() {
        synchronized (MONITOR) {
            return CHUNK_KEYS.isEmpty() && REGION_KEYS.isEmpty() && PAYLOAD_TARGETS.isEmpty();
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

    public static void watchPayload(final PayloadWatchTarget target) {
        synchronized (MONITOR) {
            PAYLOAD_TARGETS.add(target);
        }
    }

    public static boolean hasPayloadWatches() {
        synchronized (MONITOR) {
            return !PAYLOAD_TARGETS.isEmpty();
        }
    }

    public static List<PayloadWatchTarget> watchedPayloads() {
        synchronized (MONITOR) {
            return List.copyOf(PAYLOAD_TARGETS);
        }
    }

    public static List<PayloadWatchTarget> watchedPayloadsForChunk(
            final String worldId,
            final DebugChunkKey chunkKey
    ) {
        synchronized (MONITOR) {
            return PAYLOAD_TARGETS.stream()
                    .filter(target -> target.matchesWorld(worldId))
                    .filter(target -> target.type() == PayloadWatchType.ENTITY || chunkKey.equals(target.chunkKey()))
                    .toList();
        }
    }

    public static PayloadWatchTarget watchedBlock(
            final String worldId,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        synchronized (MONITOR) {
            for (final PayloadWatchTarget target : PAYLOAD_TARGETS) {
                if (target.matchesBlock(worldId, PayloadWatchType.BLOCK, blockX, blockY, blockZ)) {
                    return target;
                }
            }
        }
        return null;
    }

    public static PayloadWatchTarget watchedBlockEntity(
            final String worldId,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        synchronized (MONITOR) {
            for (final PayloadWatchTarget target : PAYLOAD_TARGETS) {
                if (target.matchesBlock(worldId, PayloadWatchType.BLOCK_ENTITY, blockX, blockY, blockZ)) {
                    return target;
                }
            }
        }
        return null;
    }

    public static PayloadWatchTarget watchedEntity(
            final String worldId,
            final String entityUuid
    ) {
        synchronized (MONITOR) {
            for (final PayloadWatchTarget target : PAYLOAD_TARGETS) {
                if (target.matchesEntity(worldId, entityUuid)) {
                    return target;
                }
            }
        }
        return null;
    }

    public static boolean matches(final ChunkTraceEvent event) {
        synchronized (MONITOR) {
            return matchesChunk(event) || matchesRegion(event) || matchesPayload(event);
        }
    }

    private static boolean matchesChunk(final ChunkTraceEvent event) {
        return event.chunkKey() != null && CHUNK_KEYS.contains(event.chunkKey());
    }

    private static boolean matchesRegion(final ChunkTraceEvent event) {
        return event.regionKey() != null && REGION_KEYS.contains(event.regionKey());
    }

    private static boolean matchesPayload(final ChunkTraceEvent event) {
        return event.payloadWatchTarget() != null && PAYLOAD_TARGETS.contains(event.payloadWatchTarget());
    }
}
