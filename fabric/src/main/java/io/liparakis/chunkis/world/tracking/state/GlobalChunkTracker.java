package io.liparakis.chunkis.world.tracking.state;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Tracks the live dirty chunk deltas that Chunkis currently owns.
 */
public final class GlobalChunkTracker {

    private static final String SOURCE = "GlobalChunkTracker";
    private static final String MUTATION_SOURCE = "GlobalChunkTracker#mutation";

    private static final ConcurrentHashMap<DimensionChunkKey, ChunkDelta<?, ?>> dirtyDeltas =
            new ConcurrentHashMap<>();

    private static final GlobalChunkUnloadCache unloadCache =
            new GlobalChunkUnloadCache(key -> GlobalChunkTrackerTrace.traceTracker(
                    key,
                    ChunkTraceReason.TRACKER_UNLOAD_CACHE_EVICT,
                    "evicted delta from unload cache due to capacity"
                                                                                  ));

    private GlobalChunkTracker() {
        throw new AssertionError("Utility class");
    }

    public static void markDirty(final WorldChunk chunk) {
        markDirty(chunk, MUTATION_SOURCE);
    }

    public static void markDirty(final WorldChunk chunk, final String source) {
        Objects.requireNonNull(chunk, "chunk");
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }
        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        if (shouldSkipDelta(delta)) {
            return;
        }
        if (shouldBypassUnownedDelta(chunk.getWorld().getRegistryKey(), chunk.getPos(), delta, source)) {
            return;
        }
        ChunkOwnershipTraceHelper.traceDecision(
                chunk.getWorld().getRegistryKey(),
                chunk.getPos(),
                "CLAIMED",
                ChunkTraceReason.valueOf(delta.getOwnershipReason()),
                source,
                delta,
                null
                                               );
        putDeltaIfNeeded(keyOf(chunk.getWorld().getRegistryKey(), chunk.getPos()), delta, source, chunk);
    }

    @SuppressWarnings("unused")
    public static void addDelta(
            final World world,
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta
                               ) {
        addDelta(world, pos, delta, MUTATION_SOURCE);
    }

    public static void addDelta(
            final World world,
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta,
            final String source
                               ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        if (delta == null) {
            return;
        }
        if (shouldBypassUnownedDelta(world.getRegistryKey(), pos, delta, source)) {
            return;
        }
        delta.markDirty(source);
        putDeltaIfNeeded(keyOf(world.getRegistryKey(), pos), delta, source, null);
    }

    static void addDelta(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ,
            final ChunkDelta<?, ?> delta,
            final String source
                        ) {
        if (delta == null) {
            return;
        }
        if (shouldBypassUnownedDelta(dimension, DebugChunkKeys.of(chunkX, chunkZ), delta, source)) {
            return;
        }
        delta.markDirty(source);
        final DimensionChunkKey key = keyOf(dimension, chunkX, chunkZ);
        GlobalChunkTrackerTrace.assertInvalidSparsePayloadWithoutBase(key, delta, source);
        final DebugChunkKey debugKey = key.debugChunkKey();
        final ChunkDelta<?, ?> existing = dirtyDeltas.get(key);

        if (existing == delta) {
            putInUnloadCache(key, delta, false, null);
            return;
        }

        if (shouldKeepExistingAuthoritativeDelta(existing, delta)) {
            GlobalChunkTrackerTrace.traceTracker(
                    dimension,
                    debugKey,
                    ChunkTraceReason.AUTHORITATIVE_DELTA_KEPT,
                    "kept authoritative tracked delta over weaker replacement",
                    source,
                    null
                                                );
            putInUnloadCache(key, existing, true, null);
            return;
        }

        dirtyDeltas.put(key, delta);
        GlobalChunkTrackerTrace.traceTracker(
                dimension,
                debugKey,
                ChunkTraceReason.TRACKER_DIRTY_MAP_PUT,
                "registered dirty delta",
                source,
                null
                                            );
        putInUnloadCache(key, delta, false, null);
    }

    public static void markSaved(final World world, final ChunkPos position) {
        markSaved(world.getRegistryKey(), position.x, position.z);
    }

    public static void markSavedIfUnchanged(
            final World world,
            final ChunkPos position,
            final ChunkDelta<?, ?> liveDelta,
            final long generation
                                           ) {
        markSavedIfUnchanged(world.getRegistryKey(), position.x, position.z, liveDelta, generation);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isCurrentDirtyDelta(
            final World world,
            final ChunkPos position,
            final ChunkDelta<?, ?> expected,
            final long generation
                                             ) {
        if (world == null || position == null || expected == null) {
            return false;
        }
        return isCurrentDirtyDelta(world.getRegistryKey(), position.x, position.z, expected, generation);
    }

    static boolean isCurrentDirtyDelta(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ,
            final ChunkDelta<?, ?> expected,
            final long generation
                                      ) {
        if (dimension == null || expected == null) {
            return false;
        }
        final ChunkDelta<?, ?> active = dirtyDeltas.get(keyOf(dimension, chunkX, chunkZ));
        return active == expected
                && expected.getMutationGeneration() == generation;
    }

    private static boolean shouldBypassUnownedDelta(
            final RegistryKey<World> dimension,
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta,
            final String source
                                                   ) {
        if (ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            return false;
        }
        ChunkOwnershipTraceHelper.traceDecision(
                dimension,
                pos,
                "BYPASSED",
                ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED,
                source,
                delta,
                null
                                               );
        return true;
    }

    private static boolean shouldBypassUnownedDelta(
            final RegistryKey<World> dimension,
            final DebugChunkKey chunkKey,
            final ChunkDelta<?, ?> delta,
            final String source
                                                   ) {
        if (ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            return false;
        }
        ChunkOwnershipTraceHelper.traceDecision(
                dimension,
                chunkKey,
                "BYPASSED",
                ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED,
                source,
                delta,
                null
                                               );
        return true;
    }

    static void markSaved(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ
                         ) {
        final DimensionChunkKey key = keyOf(dimension, chunkX, chunkZ);
        final DebugChunkKey debugKey = key.debugChunkKey();
        if (dirtyDeltas.remove(key) != null) {
            GlobalChunkTrackerTrace.traceTracker(
                    dimension,
                    debugKey,
                    ChunkTraceReason.TRACKER_MARK_SAVED,
                    "removed dirty delta after save",
                    SOURCE,
                    null
                                                );
        }
        invalidateUnloadCache(key, "invalidated unload cache after save");
    }

    static void markSavedIfUnchanged(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ,
            final ChunkDelta<?, ?> liveDelta,
            final long generation
                                    ) {
        if (liveDelta == null) {
            return;
        }
        final DebugChunkKey debugKey = DebugChunkKeys.of(chunkX, chunkZ);
        dirtyDeltas.computeIfPresent(
                keyOf(dimension, chunkX, chunkZ), (key, active) -> {
                    if (active != liveDelta) {
                        GlobalChunkTrackerTrace.traceTracker(
                                dimension,
                                debugKey,
                                ChunkTraceReason.STALE_GENERATION_IGNORED,
                                "ignored async save completion for replaced delta",
                                SOURCE,
                                null
                                                            );
                        return active;
                    }
                    if (liveDelta.markSavedIfGeneration(generation)) {
                        GlobalChunkTrackerTrace.traceTracker(
                                dimension,
                                debugKey,
                                ChunkTraceReason.TRACKER_MARK_SAVED,
                                "removed dirty delta after unchanged async save",
                                SOURCE,
                                null
                                                            );
                        invalidateUnloadCache(key, "invalidated unload cache after unchanged async save");
                        return null;
                    }
                    GlobalChunkTrackerTrace.traceTracker(
                            dimension,
                            debugKey,
                            ChunkTraceReason.STALE_GENERATION_IGNORED,
                            "ignored async save completion for advanced generation " + generation,
                            SOURCE,
                            null
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
        unloadCache.clear();
    }

    public static void forgetChunk(final World world, final ChunkPos position) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(position, "position");

        final DimensionChunkKey key = keyOf(world.getRegistryKey(), position);
        dirtyDeltas.remove(key);
        unloadCache.remove(key);
    }

    public static ChunkDelta<?, ?> getDelta(final World world, final ChunkPos position) {
        return getDelta(world.getRegistryKey(), position.x, position.z);
    }

    static ChunkDelta<?, ?> getDelta(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ
                                    ) {
        final DebugChunkKey debugKey = DebugChunkKeys.of(chunkX, chunkZ);
        final DimensionChunkKey key = keyOf(dimension, chunkX, chunkZ);
        final ChunkDelta<?, ?> active = dirtyDeltas.get(key);
        if (active != null) {
            return active;
        }
        final ChunkDelta<?, ?> cached = getFromUnloadCache(key);
        if (cached != null && !cached.isDirty()) {
            invalidateUnloadCache(key, "invalidated clean unload-cache delta before load");
            GlobalChunkTrackerTrace.traceTracker(
                    dimension,
                    debugKey,
                    ChunkTraceReason.TRACKER_UNLOAD_CACHE_MISS,
                    "ignored clean unload-cache delta and fell back to storage",
                    SOURCE,
                    null
                                                );
            return null;
        }
        GlobalChunkTrackerTrace.traceTracker(
                dimension,
                debugKey,
                cached != null
                        ? ChunkTraceReason.TRACKER_UNLOAD_CACHE_HIT
                        : ChunkTraceReason.TRACKER_UNLOAD_CACHE_MISS,
                cached != null
                        ? "resolved dirty delta from unload cache"
                        : "no delta in unload cache",
                SOURCE,
                null
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
            if (key.dimension().equals(dimension)) {
                pending.put(key.chunkPos(), (ChunkDelta<T, N>) entry.getValue());
            }
        }

        return pending;
    }

    private static void putDeltaIfNeeded(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String source,
            final WorldChunk chunk
                                        ) {
        if (!dirtyDeltas.containsKey(key)) {
            GlobalChunkTrackerTrace.traceFirstDirtyMutation(key, delta, source);
        }
        GlobalChunkTrackerTrace.assertInvalidSparsePayloadWithoutBase(key, delta, source);
        final ChunkDelta<?, ?> existing = dirtyDeltas.get(key);

        if (existing == delta) {
            putInUnloadCache(key, delta, false, null);
            return;
        }

        if (shouldKeepExistingAuthoritativeDelta(existing, delta)) {
            GlobalChunkTrackerTrace.traceTracker(
                    key,
                    ChunkTraceReason.AUTHORITATIVE_DELTA_KEPT,
                    "kept authoritative tracked delta over weaker replacement"
                                                );
            putInUnloadCache(key, existing, true, null);
            return;
        }

        dirtyDeltas.put(key, delta);
        GlobalChunkTrackerTrace.traceOwnershipBoundaryDecision(
                key,
                delta,
                "CLAIMED",
                source + "#dirtyMapPut"
                                                              );
        GlobalChunkTrackerTrace.traceTrackedDeltaStage(
                key,
                delta,
                "dirty-tracker",
                source,
                "delta entered dirty tracker"
                                                      );
        if (chunk != null) {
            GlobalChunkTrackerTrace.traceTrackedLiveChunk(
                    chunk,
                    delta,
                    "dirty-tracker-live",
                    source
                                                         );
        }
        GlobalChunkTrackerTrace.traceTracker(
                key,
                ChunkTraceReason.TRACKER_DIRTY_MAP_PUT,
                "registered dirty delta",
                source
                                            );
        putInUnloadCache(key, delta, true, chunk);
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
        final DimensionChunkKey key = keyOf(dimension, chunkX, chunkZ);
        if (!hadActiveDirtyDelta) {
            return;
        }
        if (getFromUnloadCache(key) != null) {
            return;
        }
        GlobalChunkTrackerTrace.traceTracker(
                dimension,
                key.debugChunkKey(),
                ChunkTraceReason.TRACKER_CHUNK_UNLOADED,
                "world chunk unloaded while dirty delta remained tracked without unload-cache mirror",
                SOURCE,
                true
                                            );
    }

    private static void putInUnloadCache(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final boolean tracePut,
            final WorldChunk chunk
                                        ) {
        unloadCache.put(key, delta);
        GlobalChunkTrackerTrace.traceOwnershipBoundaryDecision(
                key,
                delta,
                ChunkDeltaOwnership.hasChunkisOwnedState(delta) ? "CLAIMED" : "BYPASSED",
                SOURCE + "#putInUnloadCache"
                                                              );
        GlobalChunkTrackerTrace.traceTrackedDeltaStage(
                key,
                delta,
                "unload-cache",
                SOURCE + "#putInUnloadCache",
                "delta stored in unload cache"
                                                      );
        if (chunk != null) {
            GlobalChunkTrackerTrace.traceTrackedLiveChunk(
                    chunk,
                    delta,
                    "unload-cache-live",
                    SOURCE + "#putInUnloadCache"
                                                         );
        }
        if (tracePut) {
            GlobalChunkTrackerTrace.traceTracker(
                    key,
                    ChunkTraceReason.TRACKER_UNLOAD_CACHE_PUT,
                    "stored delta in unload cache"
                                                );
        }
    }

    private static ChunkDelta<?, ?> getFromUnloadCache(final DimensionChunkKey key) {
        return unloadCache.get(key);
    }

    private static void invalidateUnloadCache(
            final DimensionChunkKey key,
            final String message
                                             ) {
        final boolean removed;
        removed = unloadCache.remove(key);
        if (removed) {
            GlobalChunkTrackerTrace.traceTracker(
                    key.dimension(),
                    key.debugChunkKey(),
                    ChunkTraceReason.TRACKER_UNLOAD_CACHE_INVALIDATED,
                    message,
                    SOURCE,
                    null
                                                );
        }
    }

    private static boolean shouldSkipDelta(final ChunkDelta<?, ?> delta) {
        return delta == null || (delta.isEmpty() && !delta.isDirty());
    }

    private static DimensionChunkKey keyOf(
            final RegistryKey<World> dimension,
            final ChunkPos pos
                                          ) {
        return DimensionChunkKey.of(dimension, pos);
    }

    private static DimensionChunkKey keyOf(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ
                                          ) {
        return DimensionChunkKey.of(dimension, chunkX, chunkZ);
    }
}


