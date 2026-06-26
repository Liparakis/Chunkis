package io.liparakis.chunkis.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.storage.CisNbtUtil;
import io.liparakis.chunkis.storage.ChunkDeltaOwnership;
import io.liparakis.chunkis.storage.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.storage.DeltaPersistenceGuard;
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
    private static final String MUTATION_SOURCE = "GlobalChunkTracker#mutation";
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
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            ChunkOwnershipTraceHelper.traceDecision(
                    chunk.getWorld().getRegistryKey(),
                    chunk.getPos(),
                    "BYPASSED",
                    ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED,
                    source,
                    delta,
                    null
            );
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
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            ChunkOwnershipTraceHelper.traceDecision(
                    world.getRegistryKey(),
                    pos,
                    "BYPASSED",
                    ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED,
                    source,
                    delta,
                    null
            );
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
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            ChunkOwnershipTraceHelper.traceDecision(
                    dimension,
                    new ChunkPos(chunkX, chunkZ),
                    "BYPASSED",
                    ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED,
                    source,
                    delta,
                    null
            );
            return;
        }
        delta.markDirty(source);
        final DimensionChunkKey key = keyOf(dimension, chunkX, chunkZ);
        assertInvalidSparsePayloadWithoutBase(key, delta, source);
        final DebugChunkKey debugKey = new DebugChunkKey(chunkX, chunkZ);
        final ChunkDelta<?, ?> existing = dirtyDeltas.get(key);

        if (existing == delta) {
            putInUnloadCache(key, delta, false, null);
            return;
        }

        if (shouldKeepExistingAuthoritativeDelta(existing, delta)) {
            traceTracker(
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
        traceTracker(
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

    static void markSaved(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ
    ) {
        final DimensionChunkKey key = keyOf(dimension, chunkX, chunkZ);
        final DebugChunkKey debugKey = new DebugChunkKey(chunkX, chunkZ);
        if (dirtyDeltas.remove(key) != null) {
            traceTracker(
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
        final DebugChunkKey debugKey = new DebugChunkKey(chunkX, chunkZ);
        dirtyDeltas.computeIfPresent(
                keyOf(dimension, chunkX, chunkZ), (key, active) -> {
                    if (active != liveDelta) {
                        traceTracker(
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
                        traceTracker(
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
                    traceTracker(
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
        synchronized (unloadCache) {
            unloadCache.clear();
        }
    }

    public static ChunkDelta<?, ?> getDelta(final World world, final ChunkPos position) {
        return getDelta(world.getRegistryKey(), position.x, position.z);
    }

    static ChunkDelta<?, ?> getDelta(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ
    ) {
        final DebugChunkKey debugKey = new DebugChunkKey(chunkX, chunkZ);
        final DimensionChunkKey key = keyOf(dimension, chunkX, chunkZ);
        final ChunkDelta<?, ?> active = dirtyDeltas.get(key);
        if (active != null) {
            return active;
        }
        final ChunkDelta<?, ?> cached = getFromUnloadCache(key);
        if (cached != null && !cached.isDirty()) {
            invalidateUnloadCache(key, "invalidated clean unload-cache delta before load");
            traceTracker(
                    dimension,
                    debugKey,
                    ChunkTraceReason.TRACKER_UNLOAD_CACHE_MISS,
                    "ignored clean unload-cache delta and fell back to storage",
                    SOURCE,
                    null
            );
            return null;
        }
        traceTracker(
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
            if (key.dimension.equals(dimension)) {
                pending.put(new ChunkPos(key.chunkKey), (ChunkDelta<T, N>) entry.getValue());
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
            traceFirstDirtyMutation(key, delta, source);
        }
        assertInvalidSparsePayloadWithoutBase(key, delta, source);
        final ChunkDelta<?, ?> existing = dirtyDeltas.get(key);

        if (existing == delta) {
            putInUnloadCache(key, delta, false, null);
            return;
        }

        if (shouldKeepExistingAuthoritativeDelta(existing, delta)) {
            traceTracker(
                    key,
                    ChunkTraceReason.AUTHORITATIVE_DELTA_KEPT,
                    "kept authoritative tracked delta over weaker replacement"
            );
            putInUnloadCache(key, existing, true, null);
            return;
        }

        dirtyDeltas.put(key, delta);
        traceOwnershipBoundaryDecision(
                key,
                delta,
                "CLAIMED",
                source + "#dirtyMapPut"
        );
        PayloadWatchTracer.traceDeltaStage(
                key.dimension.getValue().toString(),
                new ChunkPos(unpackChunkX(key.chunkKey), unpackChunkZ(key.chunkKey)),
                castBlockDelta(delta),
                null,
                ChunkTraceEventType.WATCH_CAPTURED,
                "dirty-tracker",
                source,
                "delta entered dirty tracker",
                null
        );
        if (chunk != null) {
            PayloadWatchTracer.traceLiveChunkState(
                    chunk,
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "dirty-tracker-live",
                    source,
                    null,
                    castBlockDelta(delta)
            );
        }
        traceTracker(
                key,
                ChunkTraceReason.TRACKER_DIRTY_MAP_PUT,
                "registered dirty delta",
                source
        );
        putInUnloadCache(key, delta, true, chunk);
    }

    private static void traceTracker(
            final DimensionChunkKey key,
            final ChunkTraceReason reason,
            final String message
    ) {
        traceTracker(key.dimension, new ChunkPos(key.chunkKey), reason, message, SOURCE, null);
    }

    private static void traceTracker(
            final DimensionChunkKey key,
            final ChunkTraceReason reason,
            final String message,
            final String source
    ) {
        traceTracker(key.dimension, new ChunkPos(key.chunkKey), reason, message, source, null);
    }

    private static void traceTracker(
            final RegistryKey<World> dimension,
            final ChunkPos pos,
            final ChunkTraceReason reason,
            final String message,
            final String source,
            final Boolean dirtyState
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.DIRTY_TRACKING,
                ChunkTraceEventType.TRACKER_STATE_UPDATED,
                ChunkTraceSeverity.INFO,
                reason,
                source,
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
                SOURCE,
                hadActiveDirtyDelta
        );
    }

    private static void traceTracker(
            final RegistryKey<World> dimension,
            final DebugChunkKey chunkKey,
            final ChunkTraceReason reason,
            final String message,
            final String source,
            final Boolean dirtyState
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.DIRTY_TRACKING,
                ChunkTraceEventType.TRACKER_STATE_UPDATED,
                ChunkTraceSeverity.INFO,
                reason,
                source,
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
            final boolean tracePut,
            final WorldChunk chunk
    ) {
        synchronized (unloadCache) {
            unloadCache.put(key, delta);
        }
        traceOwnershipBoundaryDecision(
                key,
                delta,
                ChunkDeltaOwnership.hasChunkisOwnedState(delta) ? "CLAIMED" : "BYPASSED",
                SOURCE + "#putInUnloadCache"
        );
        PayloadWatchTracer.traceDeltaStage(
                key.dimension.getValue().toString(),
                new ChunkPos(unpackChunkX(key.chunkKey), unpackChunkZ(key.chunkKey)),
                castBlockDelta(delta),
                null,
                ChunkTraceEventType.WATCH_CAPTURED,
                "unload-cache",
                SOURCE + "#putInUnloadCache",
                "delta stored in unload cache",
                null
        );
        if (chunk != null) {
            PayloadWatchTracer.traceLiveChunkState(
                    chunk,
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "unload-cache-live",
                    SOURCE + "#putInUnloadCache",
                    null,
                    castBlockDelta(delta)
            );
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

    private static void invalidateUnloadCache(
            final DimensionChunkKey key,
            final String message
    ) {
        final boolean removed;
        synchronized (unloadCache) {
            removed = unloadCache.remove(key) != null;
        }
        if (removed) {
            traceTracker(
                    key.dimension,
                    new DebugChunkKey(unpackChunkX(key.chunkKey), unpackChunkZ(key.chunkKey)),
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

    private static void traceFirstDirtyMutation(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String source
    ) {
        if (delta == null || !delta.isDirty()) {
            return;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.FIRST_DIRTY_MUTATION,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.DELTA_BECAME_DIRTY,
                source,
                "first dirty mutation observed: " + DeltaPersistenceGuard.describeLifecycleState(delta),
                key.dimension.getValue().toString(),
                new DebugChunkKey(unpackChunkX(key.chunkKey), unpackChunkZ(key.chunkKey)),
                null,
                null,
                true,
                null
        );
    }

    private static void assertInvalidSparsePayloadWithoutBase(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String source
    ) {
        if (!DeltaPersistenceGuard.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta)) {
            return;
        }

        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.INVALID_PAYLOAD,
                source,
                "cached sparse block-entity payload without persisted base chunk NBT: "
                        + DeltaPersistenceGuard.describeDeltaShape(delta),
                key.dimension.getValue().toString(),
                new DebugChunkKey(unpackChunkX(key.chunkKey), unpackChunkZ(key.chunkKey)),
                null,
                null,
                delta.isDirty(),
                null
        );
    }

    private static void traceOwnershipBoundaryDecision(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String decision,
            final String source
    ) {
        final ChunkTraceReason reason = ChunkDeltaOwnership.hasChunkisOwnedState(delta)
                ? ChunkTraceReason.valueOf(delta.getOwnershipReason())
                : ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED;
        ChunkOwnershipTraceHelper.traceDecision(
                key.dimension,
                new DebugChunkKey(unpackChunkX(key.chunkKey), unpackChunkZ(key.chunkKey)),
                decision,
                reason,
                source,
                delta,
                null
        );
    }

    @SuppressWarnings("unchecked")
    private static ChunkDelta<net.minecraft.block.BlockState, net.minecraft.nbt.NbtCompound> castBlockDelta(
            final ChunkDelta<?, ?> delta
    ) {
        return (ChunkDelta<net.minecraft.block.BlockState, net.minecraft.nbt.NbtCompound>) delta;
    }

    private static DimensionChunkKey keyOf(
            final RegistryKey<World> dimension,
            final ChunkPos pos
    ) {
        return keyOf(dimension, pos.x, pos.z);
    }

    private static DimensionChunkKey keyOf(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ
    ) {
        return new DimensionChunkKey(
                Objects.requireNonNull(dimension, "dimension"),
                packChunkKey(chunkX, chunkZ)
        );
    }

    private static long packChunkKey(final int chunkX, final int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    private static int unpackChunkX(final long chunkKey) {
        return (int) chunkKey;
    }

    private static int unpackChunkZ(final long chunkKey) {
        return (int) (chunkKey >>> 32);
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
