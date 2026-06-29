package io.liparakis.chunkis.debug.watch;

import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks decoded watched block payloads across restore and later visibility stages.
 */
public final class BlockWatchTraceTracker {

    private static final ConcurrentHashMap<WatchTraceKey, WatchTraceState> WATCH_TRACE_STATE =
            new ConcurrentHashMap<>();

    private BlockWatchTraceTracker() {
        throw new AssertionError("Utility class");
    }

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

    public static void registerDecodedTarget(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            final String operationId,
            final BlockState expectedState
    ) {
        final WatchTraceKey key = key(worldId, chunkPos, target);
        final WatchTraceState previous = WATCH_TRACE_STATE.put(
                key,
                new WatchTraceState(operationId, expectedState)
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

    public static @Nullable String resolveOperationId(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target
    ) {
        final WatchTraceState state = get(worldId, chunkPos, target);
        return state != null ? state.operationId : null;
    }

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

    private static @Nullable WatchTraceState get(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target
    ) {
        return WATCH_TRACE_STATE.get(key(worldId, chunkPos, target));
    }

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

    private record WatchTraceKey(
            String worldId,
            int chunkX,
            int chunkZ,
            int blockX,
            int blockY,
            int blockZ
    ) {
    }

    private static final class WatchTraceState {
        private final String operationId;
        private final BlockState expectedState;
        private volatile boolean hasVisibilityEvent;
        private volatile boolean restoreDecisionSeen;
        private volatile boolean decodedPayloadNotAppliedAsserted;
        private volatile String appliedChunkInstanceId;
        private volatile boolean appliedDifferentChunkAsserted;
        private volatile boolean protoAttachedSeen;
        private volatile boolean worldConstructorConsumedSeen;

        private WatchTraceState(final String operationId, final BlockState expectedState) {
            this.operationId = operationId;
            this.expectedState = expectedState;
        }
    }
}
