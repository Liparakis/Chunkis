package io.liparakis.chunkis.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.storage.CisNbtUtil;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class GlobalChunkTracker {

    private static final String SOURCE = "GlobalChunkTracker";
    private static final int MAX_CACHE_SIZE = 10_000;

    private static final ConcurrentHashMap<DimensionChunkKey, ChunkDelta<?, ?>> dirtyDeltas =
            new ConcurrentHashMap<>();

    private static final Map<DimensionChunkKey, ChunkDelta<?, ?>> unloadCache =
            new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<DimensionChunkKey, ChunkDelta<?, ?>> eldest
                ) {
                    final boolean evict = size() > MAX_CACHE_SIZE;
                    if (evict) {
                        traceTracker(
                                eldest.getKey(),
                                ChunkTraceReason.TRACKER_UNLOAD_CACHE_EVICT,
                                "evicted delta from unload cache due to capacity"
                        );
                    }
                    return evict;
                }
            };

    private GlobalChunkTracker() {
        throw new AssertionError("Utility class");
    }

    public static void markDirty(final WorldChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }
        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        if (shouldSkipDelta(delta)) {
            return;
        }
        putDeltaIfNeeded(keyOf(chunk.getWorld().getRegistryKey(), chunk.getPos()), delta);
    }

    @SuppressWarnings("unused")
    public static void addDelta(
            final World world,
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        if (delta == null) {
            return;
        }
        delta.markDirty();
        putDeltaIfNeeded(keyOf(world.getRegistryKey(), pos), delta);
    }

    public static void markSaved(final World world, final ChunkPos position) {
        final DimensionChunkKey key = keyOf(world.getRegistryKey(), position);
        if (dirtyDeltas.remove(key) != null) {
            traceTracker(
                    world.getRegistryKey(),
                    position,
                    ChunkTraceReason.TRACKER_MARK_SAVED,
                    "removed dirty delta after save"
            );
        }
    }

    public static void markSavedIfUnchanged(
            final World world,
            final ChunkPos position,
            final ChunkDelta<?, ?> liveDelta,
            final long generation
    ) {
        if (liveDelta == null) {
            return;
        }
        dirtyDeltas.computeIfPresent(
                keyOf(world.getRegistryKey(), position), (ignored, active) -> {
                    if (active != liveDelta) {
                        traceTracker(
                                world.getRegistryKey(),
                                position,
                                ChunkTraceReason.STALE_GENERATION_IGNORED,
                                "ignored async save completion for replaced delta"
                        );
                        return active;
                    }
                    if (liveDelta.markSavedIfGeneration(generation)) {
                        traceTracker(
                                world.getRegistryKey(),
                                position,
                                ChunkTraceReason.TRACKER_MARK_SAVED,
                                "removed dirty delta after unchanged async save"
                        );
                        return null;
                    }
                    traceTracker(
                            world.getRegistryKey(),
                            position,
                            ChunkTraceReason.STALE_GENERATION_IGNORED,
                            "ignored async save completion for advanced generation " + generation
                    );
                    return active;
                }
        );
    }

    public static void noteChunkUnloaded(final WorldChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        noteChunkUnloaded(
                chunk.getWorld().getRegistryKey(),
                chunk.getPos().x,
                chunk.getPos().z,
                dirtyDeltas.containsKey(keyOf(chunk.getWorld().getRegistryKey(), chunk.getPos()))
        );
    }

    public static void clear() {
        dirtyDeltas.clear();
        synchronized (unloadCache) {
            unloadCache.clear();
        }
    }

    public static ChunkDelta<?, ?> getDelta(final World world, final ChunkPos position) {
        final DimensionChunkKey key = keyOf(world.getRegistryKey(), position);
        final ChunkDelta<?, ?> active = dirtyDeltas.get(key);
        if (active != null) {
            return active;
        }
        final ChunkDelta<?, ?> cached = getFromUnloadCache(key);
        traceTracker(
                world.getRegistryKey(),
                position,
                cached != null
                        ? ChunkTraceReason.TRACKER_UNLOAD_CACHE_HIT
                        : ChunkTraceReason.TRACKER_UNLOAD_CACHE_MISS,
                cached != null
                        ? "resolved delta from unload cache"
                        : "no delta in unload cache"
        );
        return cached;
    }

    public static ChunkDelta<?, ?> getActiveDelta(final World world, final ChunkPos position) {
        return dirtyDeltas.get(keyOf(world.getRegistryKey(), position));
    }

    @SuppressWarnings("unchecked")
    public static <T, N> Map<ChunkPos, ChunkDelta<T, N>> getPendingDeltas(final World world) {
        final RegistryKey<World> dimension = world.getRegistryKey();
        final Map<ChunkPos, ChunkDelta<T, N>> pending = new LinkedHashMap<>();

        for (final Map.Entry<DimensionChunkKey, ChunkDelta<?, ?>> entry : dirtyDeltas.entrySet()) {
            final DimensionChunkKey key = entry.getKey();
            if (key.dimension.equals(dimension)) {
                pending.put(new ChunkPos(key.chunkKey), (ChunkDelta<T, N>) entry.getValue());
            }
        }

        return pending;
    }

    private static void putDeltaIfNeeded(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta
    ) {
        final ChunkDelta<?, ?> existing = dirtyDeltas.get(key);

        if (existing == delta) {
            putInUnloadCache(key, delta, false);
            return;
        }

        if (shouldKeepExistingAuthoritativeDelta(existing, delta)) {
            traceTracker(
                    key,
                    ChunkTraceReason.AUTHORITATIVE_DELTA_KEPT,
                    "kept authoritative tracked delta over weaker replacement"
            );
            putInUnloadCache(key, existing, true);
            return;
        }

        dirtyDeltas.put(key, delta);
        traceTracker(
                key,
                ChunkTraceReason.TRACKER_DIRTY_MAP_PUT,
                "registered dirty delta"
        );
        putInUnloadCache(key, delta, true);
    }

    private static void traceTracker(
            final DimensionChunkKey key,
            final ChunkTraceReason reason,
            final String message
    ) {
        traceTracker(key.dimension, new ChunkPos(key.chunkKey), reason, message, null);
    }

    private static void traceTracker(
            final RegistryKey<World> dimension,
            final ChunkPos pos,
            final ChunkTraceReason reason,
            final String message
    ) {
        traceTracker(dimension, pos, reason, message, null);
    }

    private static void traceTracker(
            final RegistryKey<World> dimension,
            final ChunkPos pos,
            final ChunkTraceReason reason,
            final String message,
            final Boolean dirtyState
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.DIRTY_TRACKING,
                ChunkTraceEventType.TRACKER_STATE_UPDATED,
                ChunkTraceSeverity.INFO,
                reason,
                SOURCE,
                message,
                dimension.getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                null,
                dirtyState,
                null
        );
    }

    private static boolean isAuthoritative(final ChunkDelta<?, ?> delta) {
        return CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata()) || delta.isDirty();
    }

    static boolean shouldKeepExistingAuthoritativeDelta(
            final ChunkDelta<?, ?> existing,
            final ChunkDelta<?, ?> incoming
    ) {
        return existing != null && isAuthoritative(existing) && !isAuthoritative(incoming);
    }

    static void noteChunkUnloaded(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ,
            final boolean hadActiveDirtyDelta
    ) {
        traceTracker(
                dimension,
                new DebugChunkKey(chunkX, chunkZ),
                ChunkTraceReason.TRACKER_CHUNK_UNLOADED,
                hadActiveDirtyDelta
                        ? "world chunk unloaded while dirty delta remained tracked"
                        : "world chunk unloaded without active dirty delta",
                hadActiveDirtyDelta
        );
    }

    private static void traceTracker(
            final RegistryKey<World> dimension,
            final DebugChunkKey chunkKey,
            final ChunkTraceReason reason,
            final String message,
            final Boolean dirtyState
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.DIRTY_TRACKING,
                ChunkTraceEventType.TRACKER_STATE_UPDATED,
                ChunkTraceSeverity.INFO,
                reason,
                SOURCE,
                message,
                dimension.getValue().toString(),
                chunkKey,
                null,
                null,
                dirtyState,
                null
        );
    }

    private static void putInUnloadCache(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final boolean tracePut
    ) {
        synchronized (unloadCache) {
            unloadCache.put(key, delta);
        }
        if (tracePut) {
            traceTracker(
                    key,
                    ChunkTraceReason.TRACKER_UNLOAD_CACHE_PUT,
                    "stored delta in unload cache"
            );
        }
    }

    private static ChunkDelta<?, ?> getFromUnloadCache(final DimensionChunkKey key) {
        synchronized (unloadCache) {
            return unloadCache.get(key);
        }
    }

    private static boolean shouldSkipDelta(final ChunkDelta<?, ?> delta) {
        return delta == null || (delta.isEmpty() && !delta.isDirty());
    }

    private static DimensionChunkKey keyOf(
            final RegistryKey<World> dimension,
            final ChunkPos pos
    ) {
        return new DimensionChunkKey(
                Objects.requireNonNull(dimension, "dimension"),
                Objects.requireNonNull(pos, "pos").toLong()
        );
    }

    private static final class DimensionChunkKey {

        private final RegistryKey<World> dimension;
        private final long chunkKey;
        private final int hash;

        private DimensionChunkKey(final RegistryKey<World> dimension, final long chunkKey) {
            this.dimension = dimension;
            this.chunkKey = chunkKey;
            this.hash = 31 * dimension.hashCode() + Long.hashCode(chunkKey);
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DimensionChunkKey other)) {
                return false;
            }
            return chunkKey == other.chunkKey && dimension.equals(other.dimension);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
