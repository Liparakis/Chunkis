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

/**
 * Global registry for chunk deltas that still need persistence.
 *
 * <p>This tracker owns two complementary stores:</p>
 * <ul>
 *   <li><b>Dirty delta map:</b> strong references to deltas that must be saved.
 *       Entries are removed after successful persistence.</li>
 *   <li><b>Unload cache:</b> size-limited LRU cache for recently unloaded deltas.
 *       This covers the short unload/reload gap that can occur during disconnects,
 *       dimension changes, and shutdown-adjacent chunk lifecycle weirdness.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> {@link #dirtyDeltas} uses {@link ConcurrentHashMap} for
 * lock-free reads on the hot path. {@link #unloadCache} is an access-order
 * {@link LinkedHashMap} and must always be accessed under
 * {@code synchronized (unloadCache)}.</p>
 *
 * <p>Chunk objects are never retained. Only dimension-aware chunk keys and
 * {@link ChunkDelta} instances extracted from {@link ChunkisDeltaDuck} are stored.</p>
 *
 * @author Liparakis
 * @version 2.2
 *
 */
public final class GlobalChunkTracker {

    private static final String SOURCE = "GlobalChunkTracker";

    /**
     * Maximum number of recently unloaded deltas retained in {@link #unloadCache}.
     * The least-recently accessed entry is evicted once this limit is exceeded.
     */
    private static final int MAX_CACHE_SIZE = 10_000;

    /**
     * Active dirty deltas awaiting persistence, keyed by dimension-aware chunk position.
     *
     * <p>Optimized for frequent lock-free reads from save/load paths.</p>
     */
    private static final ConcurrentHashMap<DimensionChunkKey, ChunkDelta<?, ?>> dirtyDeltas =
            new ConcurrentHashMap<>();

    /**
     * LRU cache of recently unloaded deltas, keyed by dimension-aware chunk position.
     *
     * <p>Configured with access-order mode so {@code get} refreshes recency automatically.
     * All access must be guarded by {@code synchronized (unloadCache)}.</p>
     */
    private static final Map<DimensionChunkKey, ChunkDelta<?, ?>> unloadCache =
            new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<DimensionChunkKey, ChunkDelta<?, ?>> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            };

    /**
     * Utility class – not instantiable.
     */
    private GlobalChunkTracker() {
        throw new AssertionError("Utility class");
    }

    /**
     * Marks a live chunk's delta as dirty and registers it for persistence.
     *
     * <p>The chunk itself is not retained. Only its dimension-aware position key
     * and the extracted delta are stored.</p>
     *
     * <p>Dirty empty deltas are intentionally tracked: they may mean that previous
     * persisted edits were reverted and storage still needs to clear old data.</p>
     *
     * @param chunk the chunk whose delta should be tracked; must be non-null
     */
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

    /**
     * Registers an explicit delta for a position and marks it dirty.
     *
     * <p>Used when a delta is created outside a directly available chunk instance,
     * such as during storage, migration, or deserialization flows.</p>
     *
     * @param world the world owning the chunk; must be non-null
     * @param pos   the chunk position; must be non-null
     * @param delta the delta to register; {@code null} is a no-op
     */
    @SuppressWarnings("unused")
    public static void addDelta(
            final World world,
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        if (delta == null) {
            return;
        }
        delta.markDirty();
        putDeltaIfNeeded(keyOf(world.getRegistryKey(), pos), delta);
    }

    /**
     * Removes a delta from active dirty tracking after a successful synchronous save.
     *
     * <p>The unload cache is intentionally left intact. It acts as a bounded safety
     * net for late reloads and shutdown-adjacent re-saves.</p>
     *
     * @param world    the world owning the chunk
     * @param position the chunk position that was saved
     */
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

    /**
     * Removes a delta from active dirty tracking only if the same delta instance is
     * still current and its mutation generation matches the saved payload.
     *
     * <p>Intended for async save completion. The update is performed via
     * {@link ConcurrentHashMap#computeIfPresent} so a newer delta instance cannot be
     * removed by a stale async completion.</p>
     *
     * <p>{@link ChunkDelta#markSavedIfGeneration(long)} remains the source of truth
     * for whether the live delta changed since the save payload was produced.</p>
     *
     * @param world      the world owning the chunk
     * @param position   the chunk position that was saved
     * @param liveDelta  the live delta instance used to produce the save payload
     * @param generation the mutation generation captured when the payload was built
     */
    public static void markSavedIfUnchanged(
            final World world,
            final ChunkPos position,
            final ChunkDelta<?, ?> liveDelta,
            final long generation) {
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

    /**
     * Clears all tracked deltas from both the dirty map and the unload cache.
     *
     * <p>Call this when a server stops or a singleplayer world disconnects. Static
     * collections can otherwise retain up to {@value #MAX_CACHE_SIZE} cached
     * deltas indefinitely.</p>
     */
    public static void clear() {
        dirtyDeltas.clear();
        synchronized (unloadCache) {
            unloadCache.clear();
        }
    }

    /**
     * Retrieves a delta from active dirty tracking or, as a fallback, the unload
     * cache.
     *
     * <p>The dirty map is checked first because it is lock-free and authoritative
     * for pending saves.</p>
     *
     * @param world    the world owning the chunk
     * @param position the chunk position
     * @return the tracked delta, or {@code null} if none exists in either store
     */
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

    /**
     * Retrieves only the actively dirty delta for a position, skipping the unload
     * cache.
     *
     * <p>Hot save paths typically have a live chunk fallback, so paying the
     * synchronization cost of the unload cache there is unnecessary.</p>
     *
     * @param world    the world owning the chunk
     * @param position the chunk position
     * @return the active dirty delta, or {@code null}
     */
    public static ChunkDelta<?, ?> getActiveDelta(final World world, final ChunkPos position) {
        return dirtyDeltas.get(keyOf(world.getRegistryKey(), position));
    }

    /**
     * Returns a snapshot of actively dirty deltas for one world.
     *
     * <p>Avoids streams and collectors because shutdown flushes should not pay
     * lambda and allocation overhead. The returned map is a snapshot and can be
     * iterated safely while saves remove entries from the tracker concurrently.</p>
     *
     * @param world the world whose pending deltas should be returned
     * @param <T>   block/state payload type
     * @param <N>   NBT payload type
     * @return a {@link LinkedHashMap} from chunk position to dirty delta
     */
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

    /**
     * Stores {@code delta} for {@code key}, guarded by an authoritativeness check
     * that prevents a half-initialized delta from replacing a richer one.
     *
     * <p>A delta is considered <em>authoritative</em> if it has a persisted base
     * chunk snapshot or carries dirty edits. When the existing entry is authoritative
     * and the incoming one is not, the existing entry is kept and only the unload
     * cache is refreshed for recency.</p>
     *
     * <p>When the incoming delta is the same instance as the existing one, the dirty
     * map write is skipped entirely; only the unload cache is refreshed.</p>
     *
     * @param key   the dimension-aware chunk key
     * @param delta the delta to track
     */
    private static void putDeltaIfNeeded(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta) {
        final ChunkDelta<?, ?> existing = dirtyDeltas.get(key);

        if (existing == delta) {
            // Same instance already tracked – just refresh the unload cache.
            putInUnloadCache(key, delta);
            return;
        }

        if (existing != null && isAuthoritative(existing) && !isAuthoritative(delta)) {
            // Incoming delta is a downgrade (e.g. half-initialized ProtoChunk delta).
            // Keep the existing authoritative entry but refresh its unload cache recency.
            putInUnloadCache(key, existing);
            return;
        }

        dirtyDeltas.put(key, delta);
        traceTracker(
                key.dimension,
                new ChunkPos(key.chunkKey),
                ChunkTraceReason.TRACKER_DIRTY_MAP_PUT,
                "registered dirty delta"
        );
        putInUnloadCache(key, delta);
    }

    private static void traceTracker(
            final RegistryKey<World> dimension,
            final ChunkPos pos,
            final ChunkTraceReason reason,
            final String message
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
                null,
                null
        );
    }

    /**
     * Returns {@code true} if {@code delta} holds data that must not be overwritten
     * by a less-initialized delta.
     *
     * <p>A delta is authoritative when it has a persisted base chunk snapshot or
     * carries dirty edits. Either condition is sufficient.</p>
     *
     * @param delta the delta to inspect; must be non-null
     * @return {@code true} if the delta should be treated as authoritative
     */
    private static boolean isAuthoritative(final ChunkDelta<?, ?> delta) {
        return CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata()) || delta.isDirty();
    }

    /**
     * Inserts or refreshes {@code delta} in the unload cache.
     *
     * <p>In access-order mode, {@code put} counts as an access and updates recency.
     * All access must be inside this synchronized block.</p>
     *
     * @param key   the dimension-aware chunk key
     * @param delta the delta to cache
     */
    private static void putInUnloadCache(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta) {
        synchronized (unloadCache) {
            unloadCache.put(key, delta);
        }
    }

    /**
     * Retrieves a delta from the unload cache.
     *
     * <p>In access-order mode, {@link Map#get} updates recency automatically.</p>
     *
     * @param key the dimension-aware chunk key
     * @return the cached delta, or {@code null}
     */
    private static ChunkDelta<?, ?> getFromUnloadCache(final DimensionChunkKey key) {
        synchronized (unloadCache) {
            return unloadCache.get(key);
        }
    }

    /**
     * Returns {@code true} when {@code delta} should be ignored by the tracker.
     *
     * <p>A dirty empty delta is not skipped: that state can be meaningful when a
     * chunk's saved edits were removed and the storage layer still needs to persist
     * the cleanup.</p>
     *
     * @param delta the delta to inspect; may be {@code null}
     * @return {@code true} if the delta should not be tracked
     */
    private static boolean shouldSkipDelta(final ChunkDelta<?, ?> delta) {
        return delta == null || (delta.isEmpty() && !delta.isDirty());
    }

    /**
     * Creates a dimension-aware key for a chunk position.
     *
     * @param dimension the world registry key; must be non-null
     * @param pos       the chunk position; must be non-null
     * @return a stable tracker key
     */
    private static DimensionChunkKey keyOf(
            final RegistryKey<World> dimension,
            final ChunkPos pos) {
        return new DimensionChunkKey(
                Objects.requireNonNull(dimension, "dimension"),
                Objects.requireNonNull(pos, "pos").toLong()
        );
    }

    /**
     * Dimension-aware chunk key for use as a map key in both {@link #dirtyDeltas}
     * and {@link #unloadCache}.
     *
     * <p>{@link ChunkPos#toLong()} is unique only within one dimension, so the
     * dimension key participates in equality. The hash code is precomputed at
     * construction because these keys are used heavily by hash maps.</p>
     */
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
            if (this == obj) return true;
            if (!(obj instanceof DimensionChunkKey other)) return false;
            return chunkKey == other.chunkKey && dimension.equals(other.dimension);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
