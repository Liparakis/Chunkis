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

    /**
     * Log source tag identifier mapping for tracker actions.
     */
    private static final String SOURCE = "GlobalChunkTracker";

    /**
     * Log source tag identifier mapping for active mutations.
     */
    private static final String MUTATION_SOURCE = "GlobalChunkTracker#mutation";

    /**
     * Backing register mapping dirty deltas per dimension coordinates key.
     */
    private static final ConcurrentHashMap<DimensionChunkKey, ChunkDelta<?, ?>> dirtyDeltas =
            new ConcurrentHashMap<>();

    /**
     * Backing cache structure storing unloaded chunk delta states before eviction.
     */
    private static final GlobalChunkUnloadCache unloadCache =
            new GlobalChunkUnloadCache(key -> GlobalChunkTrackerTrace.traceTracker(
                    key,
                    ChunkTraceReason.TRACKER_UNLOAD_CACHE_EVICT,
                    "evicted delta from unload cache due to capacity"
            ));

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private GlobalChunkTracker() {
        throw new AssertionError("Utility class");
    }

    /**
     * Flags a target chunk dirty, invoking mutation tracking paths.
     *
     * @param chunk target world chunk
     */
    public static void markDirty(final WorldChunk chunk) {
        markDirty(chunk, MUTATION_SOURCE);
    }

    /**
     * Flags a target chunk dirty, invoking mutation tracking paths with custom attribution.
     *
     * @param chunk  target world chunk
     * @param source caller identifier tag
     */
    public static void markDirty(final WorldChunk chunk, final String source) {
        Objects.requireNonNull(chunk, "chunk");
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }
        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        if (shouldSkipDelta(delta)) {
            return;
        }
        if (shouldBypassUnownedDelta(chunk.getWorld()
                .getRegistryKey(), chunk.getPos(), delta, source)) {
            return;
        }
        ChunkOwnershipTraceHelper.traceDecision(
                chunk.getWorld()
                        .getRegistryKey(),
                chunk.getPos(),
                "CLAIMED",
                ChunkTraceReason.valueOf(delta.getOwnershipReason()),
                source,
                delta,
                null
        );
        putDeltaIfNeeded(keyOf(chunk.getWorld()
                .getRegistryKey(), chunk.getPos()), delta, source, chunk);
    }

    /**
     * Adds a chunk delta directly to tracker maps.
     *
     * @param world world ownership reference
     * @param pos   coordinates pos metadata
     * @param delta associated block delta
     */
    @SuppressWarnings("unused")
    public static void addDelta(
            final World world,
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta
    ) {
        addDelta(world, pos, delta, MUTATION_SOURCE);
    }

    /**
     * Adds a chunk delta directly to tracker maps with custom attribution.
     *
     * @param world  world ownership reference
     * @param pos    coordinates pos metadata
     * @param delta  associated block delta
     * @param source caller identifier tag
     */
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

    /**
     * Internal helper adding delta states matching key parameters.
     *
     * @param dimension registry key mapping dimension world levels
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @param delta     associated block delta
     * @param source    caller identifier tag
     */
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

    /**
     * Marks coordinate locations saved inside tracking maps.
     *
     * @param world    owning world reference
     * @param position coordinates pos metadata
     */
    public static void markSaved(final World world, final ChunkPos position) {
        markSaved(world.getRegistryKey(), position.x, position.z);
    }

    /**
     * Conditional save completion helper marking delta save status.
     *
     * @param world      owning world reference
     * @param position   coordinates pos metadata
     * @param liveDelta  active tracking delta
     * @param generation mutation sequence check index
     */
    public static void markSavedIfUnchanged(
            final World world,
            final ChunkPos position,
            final ChunkDelta<?, ?> liveDelta,
            final long generation
    ) {
        markSavedIfUnchanged(world.getRegistryKey(), position.x, position.z, liveDelta, generation);
    }

    /**
     * Checks if target is the currently active dirty delta matching expected version.
     *
     * @param world      owning world reference
     * @param position   coordinates pos metadata
     * @param expected   expected active delta
     * @param generation generation key mapping
     * @return true if matches active tracked state
     */
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

    /**
     * Checks if target is the currently active dirty delta matching expected version using dimension keys.
     *
     * @param dimension  owning dimension registry key
     * @param chunkX     chunk X coordinate
     * @param chunkZ     chunk Z coordinate
     * @param expected   expected active delta
     * @param generation generation key mapping
     * @return true if matches active tracked state
     */
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

    /**
     * Evaluates if unowned delta changes should skip active claim tracks.
     *
     * @param dimension owning dimension key
     * @param pos       coordinates pos mapping
     * @param delta     candidate delta state
     * @param source    caller identifier tag
     * @return true if delta bypasses tracking
     */
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

    /**
     * Evaluates if unowned delta changes should skip active claim tracks using debug keys.
     *
     * @param dimension owning dimension key
     * @param chunkKey  coordinates debug key mapping
     * @param delta     candidate delta state
     * @param source    caller identifier tag
     * @return true if delta bypasses tracking
     */
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

    /**
     * Removes dirty tracked delta states upon successful save completions.
     *
     * @param dimension owning dimension key
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     */
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

    /**
     * Removes dirty tracked delta states only if live generation matches version keys.
     *
     * @param dimension  owning dimension key
     * @param chunkX     chunk X coordinate
     * @param chunkZ     chunk Z coordinate
     * @param liveDelta  active live delta reference
     * @param generation checked mutation generation
     */
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

    /**
     * Logs chunk unloading events.
     *
     * @param chunk target world chunk
     */
    public static void noteChunkUnloaded(final WorldChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        noteChunkUnloaded(
                chunk.getWorld()
                        .getRegistryKey(),
                chunk.getPos().x,
                chunk.getPos().z,
                dirtyDeltas.containsKey(keyOf(chunk.getWorld()
                        .getRegistryKey(), chunk.getPos()))
        );
    }

    /**
     * Clears tracked maps and unload cache elements completely.
     */
    public static void clear() {
        dirtyDeltas.clear();
        unloadCache.clear();
    }

    /**
     * Deletes tracking records matching positions safely.
     *
     * @param world    owning world
     * @param position coordinates pos mapping
     */
    public static void forgetChunk(final World world, final ChunkPos position) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(position, "position");

        final DimensionChunkKey key = keyOf(world.getRegistryKey(), position);
        dirtyDeltas.remove(key);
        unloadCache.remove(key);
    }

    /**
     * Resolves matching chunk delta states from maps or unload caches.
     *
     * @param world    owning world reference
     * @param position coordinates pos mapping
     * @return found block delta or null
     */
    public static ChunkDelta<?, ?> getDelta(final World world, final ChunkPos position) {
        return getDelta(world.getRegistryKey(), position.x, position.z);
    }

    /**
     * Resolves matching chunk delta states using coordinates indexes.
     *
     * @param dimension owning dimension key
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @return found block delta or null
     */
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

    /**
     * Resolves active dirty delta states registered inside live mapping maps.
     *
     * @param world    owning world reference
     * @param position coordinates pos mapping
     * @return active dirty delta reference or null
     */
    public static ChunkDelta<?, ?> getActiveDelta(final World world, final ChunkPos position) {
        return dirtyDeltas.get(keyOf(world.getRegistryKey(), position));
    }

    /**
     * Collects and snapshots pending dirty deltas per dimension.
     *
     * @param <T>   block state type
     * @param <N>   NBT payload type
     * @param world owning world reference
     * @return map of dirty deltas
     */
    @SuppressWarnings("unchecked")
    public static <T, N> Map<ChunkPos, ChunkDelta<T, N>> getPendingDeltas(final World world) {
        final RegistryKey<World> dimension = world.getRegistryKey();
        final Map<ChunkPos, ChunkDelta<T, N>> pending = new LinkedHashMap<>();

        for (final Map.Entry<DimensionChunkKey, ChunkDelta<?, ?>> entry : dirtyDeltas.entrySet()) {
            final DimensionChunkKey key = entry.getKey();
            if (key.dimension()
                    .equals(dimension)) {
                pending.put(key.chunkPos(), (ChunkDelta<T, N>) entry.getValue());
            }
        }

        return pending;
    }

    /**
     * Internal helper registering modified changes safely.
     *
     * @param key    coordinates mapping key
     * @param delta  associated block delta
     * @param source caller identifier tag
     * @param chunk  target world chunk
     */
    private static void putDeltaIfNeeded(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String source,
            final WorldChunk chunk
    ) {
        final ChunkDelta<?, ?> existing = dirtyDeltas.get(key);

        if (existing == null) {
            GlobalChunkTrackerTrace.traceFirstDirtyMutation(key, delta, source);
        }
        GlobalChunkTrackerTrace.assertInvalidSparsePayloadWithoutBase(key, delta, source);

        if (existing == delta) {
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

    /**
     * Checks if target represents authoritative block/metadata states.
     *
     * @param delta candidate delta
     * @return true if authoritative
     */
    private static boolean isAuthoritative(final ChunkDelta<?, ?> delta) {
        return CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata()) || delta.isDirty();
    }

    /**
     * Checks if current tracked delta states should override incoming changes.
     *
     * @param existing current tracked delta state
     * @param incoming replacement candidate delta
     * @return true if existing should be retained
     */
    static boolean shouldKeepExistingAuthoritativeDelta(
            final ChunkDelta<?, ?> existing,
            final ChunkDelta<?, ?> incoming
    ) {
        return existing != null && isAuthoritative(existing) && !isAuthoritative(incoming);
    }

    /**
     * Logs chunk unloading events.
     *
     * @param dimension           owning dimension key
     * @param chunkX              coordinates X
     * @param chunkZ              coordinates Z
     * @param hadActiveDirtyDelta true if active dirty delta is tracked
     */
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

    /**
     * Registers delta states inside cached unload listings.
     *
     * @param key      coordinates key mapping
     * @param delta    associated block delta
     * @param tracePut true to trigger trace log output
     * @param chunk    associated live chunk reference
     */
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

    /**
     * Fetches cached delta states matching key parameters.
     *
     * @param key coordinates key mapping
     * @return cached delta or null
     */
    private static ChunkDelta<?, ?> getFromUnloadCache(final DimensionChunkKey key) {
        return unloadCache.get(key);
    }

    /**
     * Invalidates cached elements matching key parameters.
     *
     * @param key     coordinates key mapping
     * @param message invalidation reasoning detail
     */
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

    /**
     * Evaluates if delta states represent empty or clean properties.
     *
     * @param delta candidate delta
     * @return true if clean/empty
     */
    private static boolean shouldSkipDelta(final ChunkDelta<?, ?> delta) {
        return delta == null || (delta.isEmpty() && !delta.isDirty());
    }

    /**
     * Constructs DimensionChunkKey coordinates mapping.
     *
     * @param dimension registry key mapping levels
     * @param pos       ChunkPos mapping
     * @return key wrapper mapping pos
     */
    private static DimensionChunkKey keyOf(
            final RegistryKey<World> dimension,
            final ChunkPos pos
    ) {
        return DimensionChunkKey.of(dimension, pos);
    }

    /**
     * Constructs DimensionChunkKey coordinates mapping.
     *
     * @param dimension registry key mapping levels
     * @param chunkX    coordinates X
     * @param chunkZ    coordinates Z
     * @return key wrapper mapping coordinates
     */
    private static DimensionChunkKey keyOf(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ
    ) {
        return DimensionChunkKey.of(dimension, chunkX, chunkZ);
    }
}
