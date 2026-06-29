package io.liparakis.chunkis.world.tracking.state;

import io.liparakis.chunkis.core.ChunkDelta;
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

final class GlobalChunkTrackerTrace {

    private static final String SOURCE = "GlobalChunkTracker";

    private GlobalChunkTrackerTrace() {
        throw new AssertionError("Utility class");
    }

    static void traceTracker(
            final DimensionChunkKey key,
            final ChunkTraceReason reason,
            final String message
    ) {
        traceTracker(key.dimension(), key.debugChunkKey(), reason, message, SOURCE, null);
    }

    static void traceTracker(
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

    static void traceTracker(
            final DimensionChunkKey key,
            final ChunkTraceReason reason,
            final String message,
            final String source
    ) {
        traceTracker(key.dimension(), key.debugChunkKey(), reason, message, source, null);
    }

    static void traceFirstDirtyMutation(
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
                key.dimension().getValue().toString(),
                key.debugChunkKey(),
                null,
                null,
                true,
                null
        );
    }

    static void assertInvalidSparsePayloadWithoutBase(
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
                key.dimension().getValue().toString(),
                key.debugChunkKey(),
                null,
                null,
                delta.isDirty(),
                null
        );
    }

    static void traceOwnershipBoundaryDecision(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String decision,
            final String source
    ) {
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

    static void traceTrackedDeltaStage(
            final DimensionChunkKey key,
            final ChunkDelta<?, ?> delta,
            final String stage,
            final String source,
            final String message
    ) {
        PayloadWatchTracer.traceDeltaStage(
                key.dimension().getValue().toString(),
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

    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> castBlockDelta(final ChunkDelta<?, ?> delta) {
        return (ChunkDelta<BlockState, NbtCompound>) delta;
    }
}


