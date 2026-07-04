package io.liparakis.chunkis.debug.watch;

import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Tracks decoded watched block payloads across restore and later visibility stages.
 */
public final class BlockWatchTraceTracker {

    /**
     * Map tracking block trace keys against their load/visibility trace state.
     */
    private static final ConcurrentHashMap<WatchTraceKey, WatchTraceState> WATCH_TRACE_STATE =
            new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private BlockWatchTraceTracker() {
        throw new AssertionError("Utility class");
    }

    /**
     * Inspects active trace state mappings detecting any blocks that remained unrestored.
     */
    public static void checkUnrestoredAssertions() {
        for (final Map.Entry<WatchTraceKey, WatchTraceState> entry : WATCH_TRACE_STATE.entrySet()) {
            final WatchTraceState state = entry.getValue();
            if (state.protoAttachedSeen && !state.restoreDecisionSeen && !state.decodedPayloadNotAppliedAsserted) {
                state.decodedPayloadNotAppliedAsserted = true;
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.ASSERTIONS,
                        ChunkTraceEventType.ASSERTION_FAILED,
                        ChunkTraceSeverity.ERROR,
                        ChunkTraceReason.PROTO_DELTA_NOT_RESTORED,
                        "PayloadWatchTracer#checkUnrestoredAssertions",
                        "watched payload was attached to proto chunk but never reached restore (no restore decision)",
                        entry.getKey().worldId,
                        DebugChunkKeys.of(entry.getKey().chunkX, entry.getKey().chunkZ),
                        null,
                        state.operationId,
                        null,
                        null
                );
            }
        }
    }

    /**
     * Marks block load trace state indicating it successfully attached to a proto chunk.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID
     */
    public static void markProtoAttached(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            final String operationId
    ) {
        final WatchTraceState state = get(worldId, chunkPos, target);
        if (state != null && operationId.equals(state.operationId)) {
            state.protoAttachedSeen = true;
        }
    }

    /**
     * Registers a new decoded trace target mapping a load operation ID session.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID
     */
    public static void registerDecodedTarget(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            final String operationId
    ) {
        final WatchTraceKey key = key(worldId, chunkPos, target);
        final WatchTraceState previous = WATCH_TRACE_STATE.put(
                key,
                new WatchTraceState(operationId)
        );
        if (previous != null && !previous.hasVisibilityEvent) {
            traceIncompleteWatch(key, previous.operationId);
        }
        if (previous != null
                && previous.protoAttachedSeen
                && !previous.worldConstructorConsumedSeen) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.DECODED_PAYLOAD_NOT_CONSUMED_BY_WORLD_CONSTRUCTOR,
                    "PayloadWatchTracer#registerDecodedWatchTargets",
                    "decoded watched payload was attached to proto chunk but never consumed by world chunk constructor",
                    worldId,
                    DebugChunkKeys.of(chunkPos),
                    null,
                    previous.operationId,
                    null,
                    null
            );
        }
    }

    /**
     * Resolves the trace operation session ID for a target.
     *
     * @param worldId  target world dimension registry ID string
     * @param chunkPos coordinates of the chunk
     * @param target   watchpoint filter target
     * @return active operation ID string, or null
     */
    public static @Nullable String resolveOperationId(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target
    ) {
        final WatchTraceState state = get(worldId, chunkPos, target);
        return state != null ? state.operationId : null;
    }

    /**
     * Marks block visibility indicator as seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     */
    public static void markVisibilitySeen(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = get(worldId, chunkPos, target);
        if (state != null && operationId.equals(state.operationId)) {
            state.hasVisibilityEvent = true;
        }
    }

    /**
     * Marks block restore decision indicator as seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     */
    public static void markRestoreDecisionSeen(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = get(worldId, chunkPos, target);
        if (state != null && operationId.equals(state.operationId)) {
            state.restoreDecisionSeen = true;
        }
    }

    /**
     * Checks if the restore decision has been seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @return true if decision was seen
     */
    public static boolean isRestoreDecisionSeen(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId
    ) {
        if (operationId == null) {
            return false;
        }
        final WatchTraceState state = get(worldId, chunkPos, target);
        return state != null && operationId.equals(state.operationId) && state.restoreDecisionSeen;
    }

    /**
     * Asserts that a block restore decision was seen during chunk loading.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @param source      class/method trace source trigger label
     */
    public static void assertRestoreDecisionSeen(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId,
            final String source
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = get(worldId, chunkPos, target);
        if (state == null
                || !operationId.equals(state.operationId)
                || state.restoreDecisionSeen
                || state.decodedPayloadNotAppliedAsserted) {
            return;
        }
        state.decodedPayloadNotAppliedAsserted = true;
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.DECODED_PAYLOAD_NOT_APPLIED,
                source,
                "decoded watched payload reached later chunk lifecycle without restore-applied or restore-skipped",
                worldId,
                DebugChunkKeys.of(chunkPos),
                null,
                operationId,
                null,
                null
        );
    }

    /**
     * Records the applied chunk instance representation details inside tracker.
     *
     * @param worldId         target world dimension registry ID string
     * @param chunkPos        coordinates of the chunk
     * @param target          watchpoint filter target
     * @param operationId     active trace session operation ID, may be null
     * @param chunkInstanceId unique string identification representing the WorldChunk memory instance
     */
    public static void recordAppliedChunkInstance(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId,
            final String chunkInstanceId
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = get(worldId, chunkPos, target);
        if (state != null && operationId.equals(state.operationId)) {
            state.appliedChunkInstanceId = chunkInstanceId;
        }
    }

    /**
     * Asserts that subsequent updates use the same applied chunk instance.
     *
     * @param worldId               target world dimension registry ID string
     * @param chunkPos              coordinates of the chunk
     * @param target                watchpoint filter target
     * @param operationId           active trace session operation ID, may be null
     * @param source                class/method trace source trigger label
     * @param actualChunkInstanceId unique string identification representing the actual WorldChunk memory instance
     */
    public static void assertSameAppliedChunkInstance(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId,
            final String source,
            final String actualChunkInstanceId
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = get(worldId, chunkPos, target);
        if (state == null
                || !operationId.equals(state.operationId)
                || state.appliedChunkInstanceId == null
                || state.appliedChunkInstanceId.equals(actualChunkInstanceId)
                || state.appliedDifferentChunkAsserted) {
            return;
        }
        state.appliedDifferentChunkAsserted = true;
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.RESTORE_APPLIED_TO_DIFFERENT_CHUNK_INSTANCE,
                source,
                "watched payload was applied on one chunk instance but later observed on another",
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                null,
                null
        );
    }

    /**
     * Marks block load trace state indicating it was successfully consumed by a world constructor.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID
     */
    public static void markWorldConstructorConsumed(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            final String operationId
    ) {
        final WatchTraceState state = get(worldId, chunkPos, target);
        if (state != null && operationId.equals(state.operationId)) {
            state.worldConstructorConsumedSeen = true;
        }
    }

    /**
     * Resolves the WatchTraceState matching coordinates.
     *
     * @param worldId  target world dimension registry ID string
     * @param chunkPos coordinates of the chunk
     * @param target   watchpoint filter target
     * @return mapping state, or null
     */
    private static @Nullable WatchTraceState get(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target
    ) {
        return WATCH_TRACE_STATE.get(key(worldId, chunkPos, target));
    }

    /**
     * Constructs a WatchTraceKey from target coordinates.
     *
     * @param worldId  target world dimension registry ID string
     * @param chunkPos coordinates of the chunk
     * @param target   watchpoint filter target
     * @return constructed WatchTraceKey mapping coordinates
     */
    private static WatchTraceKey key(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target
    ) {
        return new WatchTraceKey(
                worldId,
                chunkPos.x,
                chunkPos.z,
                target.blockX(),
                target.blockY(),
                target.blockZ()
        );
    }

    /**
     * Asserts and logs diagnostic events showing watch logs remained incomplete.
     *
     * @param key         key mapping coordinates
     * @param operationId active trace session operation ID
     */
    private static void traceIncompleteWatch(final WatchTraceKey key, final String operationId) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.WATCH_TRACE_INCOMPLETE,
                ChunkTraceSeverity.WARN,
                ChunkTraceReason.WATCHED_PAYLOAD_TRACE_INCOMPLETE,
                "PayloadWatchTracer#registerDecodedWatchTargets",
                "decoded watched payload had no later restore/client visibility event for same load operation",
                key.worldId,
                DebugChunkKeys.of(key.chunkX, key.chunkZ),
                null,
                operationId,
                null,
                null,
                PayloadWatchTarget.block(key.worldId, key.blockX, key.blockY, key.blockZ),
                "trace-incomplete",
                "pos=" + key.blockX + ',' + key.blockY + ',' + key.blockZ
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.WATCHED_PAYLOAD_TRACE_INCOMPLETE,
                "PayloadWatchTracer#registerDecodedWatchTargets",
                "decoded watched payload had no later restore/client visibility event for same load operation",
                key.worldId,
                DebugChunkKeys.of(key.chunkX, key.chunkZ),
                null,
                operationId,
                null,
                null
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.DECODED_PAYLOAD_NOT_VISITED_BY_RESTORE,
                "PayloadWatchTracer#registerDecodedWatchTargets",
                "decoded watched payload never emitted a restore visit/apply decision before trace completion",
                key.worldId,
                DebugChunkKeys.of(key.chunkX, key.chunkZ),
                null,
                operationId,
                null,
                null
        );
    }

    /**
     * Map key containing coordinate descriptors.
     *
     * @param worldId target world dimension registry ID string
     * @param chunkX  chunk X coordinate
     * @param chunkZ  chunk Z coordinate
     * @param blockX  block X coordinate
     * @param blockY  block Y coordinate
     * @param blockZ  block Z coordinate
     */
    private record WatchTraceKey(
            String worldId,
            int chunkX,
            int chunkZ,
            int blockX,
            int blockY,
            int blockZ
    ) {

    }

    /**
     * State container mapping loaded indicators.
     */
    private static final class WatchTraceState {

        /**
         * The active trace session operation ID.
         */
        private final String operationId;

        /**
         * True if has visibility event.
         */
        private volatile boolean hasVisibilityEvent;

        /**
         * True if restore decision seen.
         */
        private volatile boolean restoreDecisionSeen;

        /**
         * True if decoded payload not applied was asserted.
         */
        private volatile boolean decodedPayloadNotAppliedAsserted;

        /**
         * The chunk instance ID where delta was applied.
         */
        private volatile String appliedChunkInstanceId;

        /**
         * True if applied different chunk was asserted.
         */
        private volatile boolean appliedDifferentChunkAsserted;

        /**
         * True if proto attached has been seen.
         */
        private volatile boolean protoAttachedSeen;

        /**
         * True if world constructor consumed has been seen.
         */
        private volatile boolean worldConstructorConsumedSeen;

        /**
         * Constructor.
         *
         * @param operationId active trace session operation ID
         */
        private WatchTraceState(final String operationId) {
            this.operationId = operationId;
        }
    }
}
