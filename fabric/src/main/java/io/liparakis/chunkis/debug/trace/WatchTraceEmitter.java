package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchType;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.debug.watch.BlockWatchTraceTracker;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Emits payload-watch trace events and tracks per-target restore/visibility state.
 * 
 * <p>Shared by the payload sub-tracers; split out of {@link PayloadWatchTracer} so the
 * facade no longer hosts helpers that its own sub-tracers call back into.</p>
 */
final class WatchTraceEmitter {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private WatchTraceEmitter() {
        throw new AssertionError("Utility class");
    }

    /**
     * Submits a trace log entry under the watch filters mapped by ChunkPos.
     *
     * @param eventType   the event type code to dispatch
     * @param stage       text label indicating the lifecycle stage name
     * @param source      class/method trace source trigger label
     * @param message     description detail text
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param operationId active trace session operation ID
     * @param target      watchpoint filter target
     * @param summary     description detail row summary text
     * @param byteSize    estimated size of payload in bytes, may be null
     */
    @SuppressWarnings("SameParameterValue")
    static void traceWatch(final ChunkTraceEventType eventType, final String stage, final String source,
            final String message, final String worldId, final ChunkPos chunkPos,
            final String operationId, final PayloadWatchTarget target, final String summary,
            final Integer byteSize) {
        traceWatch(eventType, stage, source, message, worldId, DebugChunkKeys.of(chunkPos), operationId, target,
                summary, byteSize);
    }

    /**
     * Submits a trace log entry under the watch filters mapped by DebugChunkKey.
     *
     * @param eventType   the event type code to dispatch
     * @param stage       text label indicating the lifecycle stage name
     * @param source      class/method trace source trigger label
     * @param message     description detail text
     * @param worldId     target world dimension registry ID string
     * @param chunkKey    chunk coordinate key
     * @param operationId active trace session operation ID
     * @param target      watchpoint filter target
     * @param summary     description detail row summary text
     * @param byteSize    estimated size of payload in bytes, may be null
     */
    static void traceWatch(final ChunkTraceEventType eventType, final String stage, final String source,
            final String message, final String worldId, final DebugChunkKey chunkKey,
            final String operationId, final PayloadWatchTarget target, final String summary,
            final Integer byteSize) {
        ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE, eventType,
                eventType == ChunkTraceEventType.WATCH_FAILED ? ChunkTraceSeverity.ERROR
                        : ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE, source, message, worldId, chunkKey, null, operationId, null, byteSize, target,
                stage, summary);
    }

    /**
     * Registers decoded targets inside BlockWatchTraceTracker.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param delta       chunk delta representation
     * @param operationId active trace session operation ID
     */
    static void registerDecodedWatchTargets(final String worldId, final ChunkPos chunkPos,
            final ChunkDeltaView<BlockState, NbtCompound> delta, final String operationId) {
        if (operationId == null) {
            return;
        }
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(worldId,
                DebugChunkKeys.of(chunkPos))) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = WatchPayloadMatcher.findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            BlockWatchTraceTracker.registerDecodedTarget(worldId, chunkPos, target, operationId);
            traceWatch(ChunkTraceEventType.WATCH_DECODED_DELTA_STATE, "decode-delta-state", "PayloadWatchTracer" +
                            "#registerDecodedWatchTargets", "decoded payload state recorded before restore", worldId,
                    chunkPos, operationId, target, PayloadWatchSummaries.summarizeExpectedAndActual(target,
                            expectedState,
                            null,
                            null,
                            "DECODED_DELTA_ONLY",
                            "PayloadWatchTracer"
                                    +
                                    "#registerDecodedWatchTargets",
                            Thread.currentThread()
                                    .getName(),
                            null), null);
            traceChunkIdentityAndStatus(worldId, chunkPos, target, operationId, "decode", "PayloadWatchTracer" +
                    "#registerDecodedWatchTargets", null);
        }
    }

    /**
     * Resolves the active trace session operation ID.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId raw operation ID parameter, may be null
     * @return resolved session ID
     */
    @Nullable
    static String resolveOperationId(final String worldId, final ChunkPos chunkPos, final PayloadWatchTarget target,
            @Nullable final String operationId) {
        if (operationId != null) {
            return operationId;
        }
        if (!target.hasBlockCoordinates()) {
            return null;
        }
        return BlockWatchTraceTracker.resolveOperationId(worldId, chunkPos, target);
    }

    /**
     * Marks block watch visibility indicator as seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     */
    static void markVisibilitySeen(final String worldId, final ChunkPos chunkPos, final PayloadWatchTarget target,
            @Nullable final String operationId) {
        if (operationId == null) {
            return;
        }
        BlockWatchTraceTracker.markVisibilitySeen(worldId, chunkPos, target, operationId);
    }

    /**
     * Marks block watch restore decision indicator as seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     */
    static void markRestoreDecisionSeen(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId) {
        if (operationId == null) {
            return;
        }
        BlockWatchTraceTracker.markRestoreDecisionSeen(worldId, chunkPos, target, operationId);
    }

    /**
     * Evaluates if block watch restore decision was seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @return true if restore decision registered
     */
    static boolean isRestoreDecisionSeen(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId) {
        if (operationId == null) {
            return false;
        }
        return BlockWatchTraceTracker.isRestoreDecisionSeen(worldId, chunkPos, target, operationId);
    }

    /**
     * Asserts that a block watch restore decision was seen.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @param source      class/method trace source trigger label
     */
    static void assertRestoreDecisionSeen(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final String source) {
        BlockWatchTraceTracker.assertRestoreDecisionSeen(worldId, chunkPos, target, operationId, source);
    }

    /**
     * Traces the chunk's instance identity hash code and status metadata.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @param stage       text label indicating the lifecycle stage name
     * @param source      class/method trace source trigger label
     * @param chunk       associated world chunk instance, may be null
     */
    static void traceChunkIdentityAndStatus(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final String stage, final String source, @Nullable final Chunk chunk) {
        final String chunkInstanceId = PayloadWatchSummaries.chunkInstanceId(chunk);
        final String chunkStatus = chunk != null ? String.valueOf(chunk.getStatus()) : "UNAVAILABLE";
        traceWatch(ChunkTraceEventType.WATCH_CHUNK_INSTANCE_ID, stage, source, "chunk instance identity observed",
                worldId, chunkPos, operationId, target, "chunkInstanceId=" + chunkInstanceId + " source=" + source +
                        " thread=" + Thread.currentThread()
                        .getName(), null);
        traceWatch(ChunkTraceEventType.WATCH_CHUNK_STATUS, stage, source, "chunk status observed", worldId, chunkPos,
                operationId, target,
                "chunkStatus=" + chunkStatus + " chunkInstanceId=" + chunkInstanceId + " source" + "=" + source + " "
                        +
                        "thread=" + Thread.currentThread()
                        .getName(), null);
    }

    /**
     * Traces a restore mutation lifecycle stage event.
     *
     * @param eventType     event type code to dispatch
     * @param stage         text label indicating the lifecycle stage name
     * @param chunk         loaded world chunk instance
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     * @param skipReason    custom descriptive skip reason text, may be null
     */
    static void traceRestoreMutationStage(final ChunkTraceEventType eventType, final String stage,
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, @Nullable final String skipReason) {
        traceRestoreSetBlockStage(eventType, stage, chunk, pos, previousState, expectedState, operationId, source,
                skipReason, "setNewBlockReturnValue=UNAVAILABLE_DIRECT_SECTION_WRITE");
    }

    /**
     * Traces restore set block returned values.
     *
     * @param eventType     event type code to dispatch
     * @param stage         text label indicating the lifecycle stage name
     * @param chunk         loaded world chunk instance
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     * @param skipReason    custom descriptive skip reason text, may be null
     */
    @SuppressWarnings("SameParameterValue")
    static void traceRestoreSetBlockReturned(final ChunkTraceEventType eventType, final String stage,
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, @Nullable final String skipReason) {
        traceRestoreSetBlockStage(eventType, stage, chunk, pos, previousState, expectedState, operationId, source,
                skipReason, "setBlockReturnValue=UNAVAILABLE_DIRECT_SECTION_WRITE");
    }

    /**
     * Internal implementation helper routing restore set block stages.
     *
     * @param eventType        event type code to dispatch
     * @param stage            text label indicating the lifecycle stage name
     * @param chunk            loaded world chunk instance
     * @param pos              block coordinate pos
     * @param previousState    block state prior to application, may be null
     * @param expectedState    target block state to write, may be null
     * @param operationId      active restore session operation ID
     * @param source           class/method trace source label
     * @param skipReason       custom descriptive skip reason text, may be null
     * @param returnValueLabel label description for output values
     */
    private static void traceRestoreSetBlockStage(final ChunkTraceEventType eventType, final String stage,
            final WorldChunk chunk, final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState, final String operationId,
            final String source, @Nullable final String skipReason,
            final String returnValueLabel) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final PayloadWatchTarget target = WatchPayloadMatcher.watchedBlockTarget(worldId, pos);
        if (target == null) {
            return;
        }
        markRestoreDecisionSeen(worldId, chunk.getPos(), target, operationId);
        traceChunkIdentityAndStatus(worldId, chunk.getPos(), target, operationId, stage, source, chunk);
        traceWatch(eventType, stage, source, skipReason != null ? skipReason : "restore mutation stage", worldId,
                chunk.getPos(), operationId, target,
                "pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ() + " " + "expectedState=" + expectedState +
                        " previousState=" + previousState + " newState=" + expectedState + ' ' + returnValueLabel +
                        " actualStateImmediatelyAfter=" + chunk.getBlockState(pos) + " chunkInstanceId="
                        + PayloadWatchSummaries.chunkInstanceId(chunk) + " chunkStatus=" + chunk.getStatus()
                        + " source=" + source + " thread=" + Thread.currentThread()
                        .getName() + (skipReason != null ?
                        " skipReason=" + skipReason : ""), null);
    }

    /**
     * Records the applied chunk instance representation details inside tracker.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @param chunk       associated world chunk instance
     */
    static void recordAppliedChunkInstance(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final WorldChunk chunk) {
        BlockWatchTraceTracker.recordAppliedChunkInstance(worldId, chunkPos, target, operationId,
                PayloadWatchSummaries.chunkInstanceId(chunk));
    }

    /**
     * Asserts that subsequent updates use the same applied chunk instance.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the chunk
     * @param target      watchpoint filter target
     * @param operationId active trace session operation ID, may be null
     * @param source      class/method trace source trigger label
     * @param chunk       associated world chunk instance
     */
    static void assertSameAppliedChunkInstance(final String worldId, final ChunkPos chunkPos,
            final PayloadWatchTarget target, @Nullable final String operationId,
            final String source, final WorldChunk chunk) {
        BlockWatchTraceTracker.assertSameAppliedChunkInstance(worldId, chunkPos, target, operationId, source,
                PayloadWatchSummaries.chunkInstanceId(chunk));
    }

    /**
     * Resolves the expected block state from either the explicit expected delta or the chunk's attached delta.
     *
     * @param chunk                 loaded world chunk instance
     * @param explicitExpectedDelta explicitly expected delta, if any, may be null
     * @param target                watchpoint filter target
     * @param chunkPos              coordinates of the chunk
     * @param worldId               target world dimension registry ID string
     * @return resolved expected block state, or null
     */
    @Nullable
    static BlockState resolveExpectedState(final WorldChunk chunk, @Nullable final ChunkDelta<BlockState,
                    NbtCompound> explicitExpectedDelta, final PayloadWatchTarget target, final ChunkPos chunkPos,
            final String worldId) {
        final ChunkDelta<BlockState, NbtCompound> expectedDelta = explicitExpectedDelta != null ?
                explicitExpectedDelta : attachedDelta(chunk);
        return expectedDelta != null ? WatchPayloadMatcher.findWatchedBlockState(expectedDelta, target, chunkPos, worldId) : null;
    }

    /**
     * Safely retrieves the attached delta from a WorldChunk via interface duck.
     *
     * @param chunk loaded world chunk instance
     * @return attached chunk delta state, or null
     */
    @SuppressWarnings("unchecked")
    @Nullable
    static ChunkDelta<BlockState, NbtCompound> attachedDelta(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck duck)) {
            return null;
        }
        return (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta();
    }

    /**
     * Classifies block state events by thread and environment authoritativeness.
     *
     * @param chunk loaded world chunk instance
     * @return event classification string
     */
    static String classifySetBlockStateEvent(final WorldChunk chunk) {
        if (!chunk.getWorld()
                .isClient()) {
            return "SERVER_AUTHORITATIVE_EVENT";
        }
        return Thread.currentThread()
                .getName()
                .contains("Render") ? "CLIENT_RENDER_LOCAL_VISUAL_EVENT" :
                "CLIENT_LOCAL_VISUAL_EVENT";
    }

    /**
     * Submits trace logs detailing active entity restore stage updates.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the chunk
     * @param nbt         source entity NBT
     * @param operationId active restore operation ID
     * @param eventType   event type code to dispatch
     * @param stage       lifecycle stage name
     * @param source      class/method trace source label
     * @param message     description detail text
     * @param suffix      optional custom details text to append, may be null
     */
    @SuppressWarnings("SameParameterValue")
    static void traceEntityRestoreStage(final ServerWorld world, final ChunkPos chunkPos, final NbtCompound nbt,
            final String operationId, final ChunkTraceEventType eventType,
            final String stage, final String source, final String message,
            @Nullable final String suffix) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String entityUuid = PayloadWatchSummaries.entityUuid(nbt);
        if (entityUuid == null) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(PayloadWatchSummaries.worldId(world),
                entityUuid);
        if (target == null) {
            return;
        }
        traceWatch(eventType, stage, source, message, PayloadWatchSummaries.worldId(world), chunkPos, operationId,
                target,
                PayloadWatchSummaries.summarizeEntity(target, nbt) + " chunkStatus=entity-restore" + " source"
                        + "=" + source + " thread=" + Thread.currentThread()
                        .getName() + (suffix != null ?
                        " " + suffix : ""), null);
    }

    /**
     * Resolves live entity instances inside world by UUID string.
     *
     * @param world      target world instance
     * @param entityUuid target entity UUID string
     * @return resolved entity instance if found, or null
     */
    @Nullable
    static Entity resolveWatchedEntity(final ServerWorld world, final String entityUuid) {
        try {
            return world.getEntity(UUID.fromString(entityUuid));
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }
}
