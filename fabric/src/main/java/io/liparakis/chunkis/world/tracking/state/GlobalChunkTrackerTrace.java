package io.liparakis.chunkis.world.tracking.state;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Emits debug traces summarizing chunk tracking lifecycle events.
 */
final class GlobalChunkTrackerTrace {

    /**
     * Log source tag identifier mapping for tracker tracing actions.
     */
    private static final String SOURCE = "GlobalChunkTracker";

    /** Stores info. */
    private static final ChunkTraceSeverity INFO = ChunkTraceSeverity.INFO;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private GlobalChunkTrackerTrace() {
        throw new AssertionError("Utility class");
    }

    /**
     * Emits a trace log for a tracker state update.
     *
     * @param key     coordinates key mapping
     * @param reason  structural reason code
     * @param message description text reasoning
     */
    static void traceTracker(
            final DimensionChunkKey key,
            final ChunkTraceReason reason,
            final String message
    ) {
        traceTracker(key.dimension(), key.debugChunkKey(), reason, message, SOURCE, null);
    }

    /**
     * Emits a trace log for a tracker state update with custom attribution.
     *
     * @param dimension  owning dimension registry key
     * @param chunkKey   coordinates debug key mapping
     * @param reason     structural reason code
     * @param message    description text reasoning
     * @param source     caller identifier tag
     * @param dirtyState true if the delta is dirty
     */
    static void traceTracker(
            final RegistryKey<World> dimension,
            final DebugChunkKey chunkKey,
            final ChunkTraceReason reason,
            final String message,
            final String source,
            final Boolean dirtyState
    ) {
        if (!ChunkisDebugConfig.allows(ChunkisDebugDomain.DIRTY_TRACKING, INFO)) {
            return;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.DIRTY_TRACKING,
                ChunkTraceEventType.TRACKER_STATE_UPDATED,
                INFO,
                reason,
                source,
                message,
                dimension.getValue()
                        .toString(),
                chunkKey,
                null,
                null,
                dirtyState,
                null
        );
    }

    /**
     * Emits a trace log for a tracker state update with custom source.
     *
     * @param key     coordinates key mapping
     * @param reason  structural reason code
     * @param message description text reasoning
     * @param source  caller identifier tag
     */
    @SuppressWarnings("SameParameterValue")
    static void traceTracker(
            final DimensionChunkKey key,
            final ChunkTraceReason reason,
            final String message,
            final String source
    ) {
        traceTracker(key.dimension(), key.debugChunkKey(), reason, message, source, null);
    }

    /**
     * Traces the first dirty mutation observed on a chunk delta.
     *
     * @param key    coordinates key mapping
     * @param delta  associated block delta
     * @param source caller identifier tag
     */
    static void traceFirstDirtyMutation(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String source
    ) {
        if (delta == null || !delta.isDirty()
                || !ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, INFO)) {
            return;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.FIRST_DIRTY_MUTATION,
                INFO,
                ChunkTraceReason.DELTA_BECAME_DIRTY,
                source,
                "first dirty mutation observed: " + DeltaPersistenceGuard.describeLifecycleState(delta),
                key.dimension()
                        .getValue()
                        .toString(),
                key.debugChunkKey(),
                null,
                null,
                true,
                null
        );
    }

    /**
     * Asserts that a delta does not carry an invalid sparse payload without base.
     *
     * @param key    coordinates key mapping
     * @param delta  associated block delta
     * @param source caller identifier tag
     */
    static void assertInvalidSparsePayloadWithoutBase(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String source
    ) {
        if (!DeltaPersistenceGuard.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta)) {
            return;
        }
        if (!ChunkisDebugConfig.allows(ChunkisDebugDomain.ASSERTIONS, ChunkTraceSeverity.ERROR)) {
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
                key.dimension()
                        .getValue()
                        .toString(),
                key.debugChunkKey(),
                null,
                null,
                delta.isDirty(),
                null
        );
    }

    /**
     * Traces the ownership boundary decision.
     *
     * @param key      coordinates key mapping
     * @param delta    associated block delta
     * @param decision decision code string
     * @param source   caller identifier tag
     */
    static void traceOwnershipBoundaryDecision(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String decision,
            final String source
    ) {
        if (!ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, INFO)) {
            return;
        }
        final ChunkTraceReason reason = ChunkDeltaOwnership.hasChunkisOwnedState(delta)
                ? ChunkTraceReason.valueOf(delta.getOwnershipReason())
                : ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED;
        ChunkOwnershipTraceHelper.traceDecision(
                key.dimension(),
                key.debugChunkKey(),
                decision,
                reason,
                source,
                delta,
                null
        );
    }

    /**
     * Traces the stage of a tracked delta.
     *
     * @param key     coordinates key mapping
     * @param delta   associated block delta
     * @param stage   stage label string
     * @param source  caller identifier tag
     * @param message detail description text
     */
    static void traceTrackedDeltaStage(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String stage,
            final String source,
            final String message
    ) {
        PayloadWatchTracer.traceDeltaStage(
                key.dimension()
                        .getValue()
                        .toString(),
                key.debugChunkKey(),
                key.chunkStartX(),
                key.chunkStartZ(),
                castBlockDelta(delta),
                null,
                ChunkTraceEventType.WATCH_CAPTURED,
                stage,
                source,
                message,
                null
        );
    }

    /**
     * Traces the state of a live world chunk.
     *
     * @param chunk  associated live world chunk
     * @param delta  associated block delta
     * @param stage  stage label string
     * @param source caller identifier tag
     */
    static void traceTrackedLiveChunk(
            final WorldChunk chunk,
            final ChunkDelta<?, ?> delta,
            final String stage,
            final String source
    ) {
        PayloadWatchTracer.traceLiveChunkState(
                chunk,
                ChunkTraceEventType.WATCH_CAPTURED,
                stage,
                source,
                null,
                castBlockDelta(delta)
        );
    }

    /**
     * Safely casts a generic chunk delta to a block state / NBT payload delta.
     *
     * @param delta delta to cast
     * @return casted delta
     */
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> castBlockDelta(final ChunkDelta<?, ?> delta) {
        return (ChunkDelta<BlockState, NbtCompound>) delta;
    }
}
