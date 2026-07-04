package io.liparakis.chunkis.debug.watch;

import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Process-local registry of chunk trace watchpoints used by the debug tracing layer.
 *
 * <p>The registry stores watched chunks, regions, and payload targets in insertion order while
 * preventing duplicates. All state is static because watchpoints are debug configuration shared
 * by trace producers in the current JVM. Every read and write is guarded by {@link #MONITOR};
 * callers receive immutable snapshots instead of live collections.</p>
 *
 * <p>This class intentionally keeps a small API with nullable return values for lookup methods to
 * preserve the existing behavior and avoid introducing optional allocation in tracing paths.</p>
 */
public final class ChunkTraceWatchpoints {

    /**
     * Single lock guarding all watchpoint sets.
     *
     * <p>The three sets must be read consistently by {@link #matches(ChunkTraceEvent)}, so using
     * one monitor is simpler and safer than separate locks. These collections are expected to be
     * small debug watch lists, so lock contention should be negligible compared with trace I/O.</p>
     */
    private static final Object MONITOR = new Object();

    /**
     * Watched chunk keys in registration order.
     *
     * <p>A {@link LinkedHashSet} preserves deterministic snapshot order and prevents duplicate
     * registrations without extra checks.</p>
     */
    private static final Set<DebugChunkKey> CHUNK_KEYS = new LinkedHashSet<>();

    /**
     * Watched region keys in registration order.
     *
     * <p>A {@link LinkedHashSet} preserves deterministic snapshot order and prevents duplicate
     * registrations without extra checks.</p>
     */
    private static final Set<DebugRegionKey> REGION_KEYS = new LinkedHashSet<>();

    /**
     * Watched block, block-entity, and entity payload targets in registration order.
     *
     * <p>Payload lookup scans this set linearly. That is intentional because watch lists are debug
     * configuration and are expected to remain small; indexing would add invalidation complexity.</p>
     */
    private static final Set<PayloadWatchTarget> PAYLOAD_TARGETS = new LinkedHashSet<>();

    /**
     * Prevents instantiation of this utility class.
     *
     * @throws AssertionError always, because instances would have no state or behavior.
     */
    private ChunkTraceWatchpoints() {
        throw new AssertionError("Utility class");
    }

    /**
     * Registers a chunk key to match future trace events.
     *
     * <p>Duplicate registrations are ignored by the backing set. Passing {@code null} preserves the
     * previous set behavior but will never match {@link #matches(ChunkTraceEvent)}, because event
     * chunk keys are checked for null before lookup.</p>
     *
     * @param chunkKey chunk key to watch; {@code null} is accepted for behavior compatibility.
     */
    public static void watchChunk(final DebugChunkKey chunkKey) {
        synchronized (MONITOR) {
            CHUNK_KEYS.add(chunkKey);
        }
    }

    /**
     * Registers a region key to match future trace events.
     *
     * <p>Duplicate registrations are ignored by the backing set. Passing {@code null} preserves the
     * previous set behavior but will never match {@link #matches(ChunkTraceEvent)}, because event
     * region keys are checked for null before lookup.</p>
     *
     * @param regionKey region key to watch; {@code null} is accepted for behavior compatibility.
     */
    public static void watchRegion(final DebugRegionKey regionKey) {
        synchronized (MONITOR) {
            REGION_KEYS.add(regionKey);
        }
    }

    /**
     * Removes all registered chunk, region, and payload watchpoints.
     *
     * <p>The operation is atomic with respect to other methods in this class because it holds the
     * shared monitor while clearing all sets.</p>
     */
    public static void clear() {
        synchronized (MONITOR) {
            CHUNK_KEYS.clear();
            REGION_KEYS.clear();
            PAYLOAD_TARGETS.clear();
        }
    }

    /**
     * Returns whether no watchpoints of any kind are registered.
     *
     * @return {@code true} when chunk, region, and payload watch sets are all empty.
     */
    public static boolean isEmpty() {
        synchronized (MONITOR) {
            return CHUNK_KEYS.isEmpty() && REGION_KEYS.isEmpty() && PAYLOAD_TARGETS.isEmpty();
        }
    }

    /**
     * Returns an immutable snapshot of watched chunks in registration order.
     *
     * @return immutable list containing the currently watched chunk keys.
     */
    public static List<DebugChunkKey> watchedChunks() {
        synchronized (MONITOR) {
            return List.copyOf(CHUNK_KEYS);
        }
    }

    /**
     * Returns an immutable snapshot of watched regions in registration order.
     *
     * @return immutable list containing the currently watched region keys.
     */
    public static List<DebugRegionKey> watchedRegions() {
        synchronized (MONITOR) {
            return List.copyOf(REGION_KEYS);
        }
    }

    /**
     * Registers a payload target to match future trace events or direct payload lookups.
     *
     * <p>Duplicate registrations are ignored by the backing set. Passing {@code null} preserves the
     * previous set behavior but can make direct payload scans throw {@link NullPointerException};
     * callers should pass valid {@link PayloadWatchTarget} instances.</p>
     *
     * @param target payload target to watch; expected to be non-null.
     */
    public static void watchPayload(final PayloadWatchTarget target) {
        synchronized (MONITOR) {
            PAYLOAD_TARGETS.add(target);
        }
    }

    /**
     * Returns whether at least one payload watch is registered.
     *
     * @return {@code true} when payload-specific matching work may be needed.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean hasPayloadWatches() {
        synchronized (MONITOR) {
            return !PAYLOAD_TARGETS.isEmpty();
        }
    }

    /**
     * Returns an immutable snapshot of watched payload targets in registration order.
     *
     * @return immutable list containing the currently watched payload targets.
     */
    public static List<PayloadWatchTarget> watchedPayloads() {
        synchronized (MONITOR) {
            return List.copyOf(PAYLOAD_TARGETS);
        }
    }

    /**
     * Returns payload watches that belong to the supplied world and chunk.
     *
     * <p>This method uses a direct loop instead of streams to avoid lambda and stream pipeline
     * allocation on a method that may be called repeatedly while tracing payload activity.</p>
     *
     * @param worldId  world identifier to match.
     * @param chunkKey chunk key to match.
     * @return immutable list of matching payload watches in registration order.
     */
    public static List<PayloadWatchTarget> watchedPayloadsForChunk(
            final String worldId,
            final DebugChunkKey chunkKey
    ) {
        synchronized (MONITOR) {
            final List<PayloadWatchTarget> matches = new ArrayList<>();
            for (final PayloadWatchTarget target : PAYLOAD_TARGETS) {
                if (target.matchesWorld(worldId) && chunkKey.equals(target.chunkKey())) {
                    matches.add(target);
                }
            }
            return List.copyOf(matches);
        }
    }

    /**
     * Finds the first watched block payload target at the supplied world-space block position.
     *
     * @param worldId world identifier to match.
     * @param blockX  world-space block x coordinate.
     * @param blockY  world-space block y coordinate.
     * @param blockZ  world-space block z coordinate.
     * @return matching target, or {@code null} when no block watch matches.
     */
    public static PayloadWatchTarget watchedBlock(
            final String worldId,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        return findBlockPayload(worldId, PayloadWatchType.BLOCK, blockX, blockY, blockZ);
    }

    /**
     * Finds the first watched block-entity payload target at the supplied world-space block position.
     *
     * @param worldId world identifier to match.
     * @param blockX  world-space block x coordinate.
     * @param blockY  world-space block y coordinate.
     * @param blockZ  world-space block z coordinate.
     * @return matching target, or {@code null} when no block-entity watch matches.
     */
    public static PayloadWatchTarget watchedBlockEntity(
            final String worldId,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        return findBlockPayload(worldId, PayloadWatchType.BLOCK_ENTITY, blockX, blockY, blockZ);
    }

    /**
     * Finds the first watched entity payload target for the supplied entity UUID.
     *
     * @param worldId    world identifier to match.
     * @param entityUuid entity UUID string to match.
     * @return matching target, or {@code null} when no entity watch matches.
     */
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
            return null;
        }
    }

    /**
     * Returns whether the supplied trace event matches any registered watchpoint.
     *
     * <p>The event is matched against chunk, region, then payload watchpoints in that order. The
     * whole check runs under the shared monitor so the result reflects one consistent registry
     * snapshot.</p>
     *
     * @param event trace event to test; expected to be non-null.
     * @return {@code true} when the event matches a watched chunk, region, or payload target.
     */
    public static boolean matches(final ChunkTraceEvent event) {
        synchronized (MONITOR) {
            return matchesChunk(event) || matchesRegion(event) || matchesPayload(event);
        }
    }

    /**
     * Finds the first block-based payload target matching the supplied world, type, and position.
     *
     * <p>This helper exists to keep block and block-entity lookup behavior identical without using
     * capturing predicates or streams in a path that may be called during tracing. It preserves
     * registration-order semantics and nullable return behavior.</p>
     *
     * @param worldId world identifier to match.
     * @param type    expected block-based payload type.
     * @param blockX  world-space block x coordinate.
     * @param blockY  world-space block y coordinate.
     * @param blockZ  world-space block z coordinate.
     * @return first matching target, or {@code null} when no target matches.
     */
    private static PayloadWatchTarget findBlockPayload(
            final String worldId,
            final PayloadWatchType type,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        synchronized (MONITOR) {
            for (final PayloadWatchTarget target : PAYLOAD_TARGETS) {
                if (target.matchesBlock(worldId, type, blockX, blockY, blockZ)) {
                    return target;
                }
            }
            return null;
        }
    }

    /**
     * Tests whether an event's chunk key is registered.
     *
     * <p>Callers must hold {@link #MONITOR}. The null check is part of the existing matching
     * behavior: a null event chunk key never matches, even if a null chunk watch was registered.</p>
     *
     * @param event trace event being evaluated.
     * @return {@code true} when the event has a watched chunk key.
     */
    private static boolean matchesChunk(final ChunkTraceEvent event) {
        return event.chunkKey() != null && CHUNK_KEYS.contains(event.chunkKey());
    }

    /**
     * Tests whether an event's region key is registered.
     *
     * <p>Callers must hold {@link #MONITOR}. The null check is part of the existing matching
     * behavior: a null event region key never matches, even if a null region watch was registered.</p>
     *
     * @param event trace event being evaluated.
     * @return {@code true} when the event has a watched region key.
     */
    private static boolean matchesRegion(final ChunkTraceEvent event) {
        return event.regionKey() != null && REGION_KEYS.contains(event.regionKey());
    }

    /**
     * Tests whether an event's payload watch target is registered.
     *
     * <p>Callers must hold {@link #MONITOR}. Payload matching uses record equality, so all target
     * components must equal a registered target.</p>
     *
     * @param event trace event being evaluated.
     * @return {@code true} when the event has a watched payload target.
     */
    private static boolean matchesPayload(final ChunkTraceEvent event) {
        return event.payloadWatchTarget() != null && PAYLOAD_TARGETS.contains(event.payloadWatchTarget());
    }
}
