package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchType;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.debug.watch.BlockWatchTraceTracker;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic tracer backing chunk loading, conversion, and world attachment lifecycles.
 */
public final class ChunkLifecyclePayloadTracer {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkLifecyclePayloadTracer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Logs trace information when a decoded delta state is attached to a proto chunk.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunk       the target proto chunk instance
     * @param delta       the attached chunk delta
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceProtoDeltaAttached(
            final String worldId,
            final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final ChunkPos chunkPos = chunk.getPos();
        PayloadWatchTracer.forEachWatchedBlockState(worldId, chunkPos, delta, match -> {
            final PayloadWatchTarget target = match.target();
            final BlockState expectedState = match.expectedState();
            BlockWatchTraceTracker.markProtoAttached(worldId, chunkPos, target, operationId);
            PayloadWatchTracer.traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "proto-attach",
                    source,
                    chunk
            );
            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_PROTO_DELTA_ATTACHED,
                    "proto-attach",
                    source,
                    "decoded watched payload attached to proto chunk",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "expectedState=" + expectedState
                            + " chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread()
                            .getName(),
                    null
            );
        });
    }

    /**
     * Logs trace information showing that a delta exists on a proto chunk before conversion takes place.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunk       the target proto chunk instance
     * @param delta       the attached chunk delta
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceProtoDeltaPresentBeforeConversion(
            final String worldId,
            final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final ChunkPos chunkPos = chunk.getPos();
        PayloadWatchTracer.forEachWatchedBlockState(worldId, chunkPos, delta, match -> {
            final PayloadWatchTarget target = match.target();
            final BlockState expectedState = match.expectedState();
            PayloadWatchTracer.traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "conversion-before",
                    source,
                    chunk
            );
            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_PROTO_DELTA_PRESENT_BEFORE_CONVERSION,
                    "conversion-before",
                    source,
                    "watched payload delta present on proto chunk before conversion",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "expectedState=" + expectedState
                            + " chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread()
                            .getName(),
                    null
            );
        });
    }

    /**
     * Logs trace information showing that a delta exists on a proto chunk after conversion took place.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunk       the target proto chunk instance
     * @param delta       the attached chunk delta
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceProtoDeltaPresentAfterConversion(
            final String worldId,
            final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final ChunkPos chunkPos = chunk.getPos();
        PayloadWatchTracer.forEachWatchedBlockState(worldId, chunkPos, delta, match -> {
            final PayloadWatchTarget target = match.target();
            final BlockState expectedState = match.expectedState();
            PayloadWatchTracer.traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "conversion-after",
                    source,
                    chunk
            );
            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_PROTO_DELTA_PRESENT_AFTER_CONVERSION,
                    "conversion-after",
                    source,
                    "watched payload delta present on proto chunk after conversion",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "expectedState=" + expectedState
                            + " chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread()
                            .getName(),
                    null
            );
        });
    }

    /**
     * Logs trace information when a delta has been attached to a live WorldChunk instance.
     *
     * @param chunk       the WorldChunk instance
     * @param delta       the attached chunk delta
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceWorldChunkDeltaAttached(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        PayloadWatchTracer.forEachWatchedBlockState(worldId, chunkPos, delta, match -> {
            final PayloadWatchTarget target = match.target();
            final BlockState expectedState = match.expectedState();
            PayloadWatchTracer.traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "worldchunk-attach",
                    source,
                    chunk
            );
            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_WORLDCHUNK_DELTA_ATTACHED,
                    "worldchunk-attach",
                    source,
                    "watched payload delta attached to WorldChunk",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "expectedState=" + expectedState
                            + " chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread()
                            .getName(),
                    null
            );
        });
    }

    /**
     * Logs trace information indicating that a WorldChunk contains no attached Chunkis delta.
     *
     * @param chunk       the WorldChunk instance
     * @param operationId active trace session operation ID
     * @param source      class/method trace source trigger label
     */
    public static void traceWorldChunkDeltaMissing(
            final WorldChunk chunk,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                DebugChunkKeys.of(chunkPos)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            PayloadWatchTracer.traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "worldchunk-missing",
                    source,
                    chunk
            );
            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_WORLDCHUNK_DELTA_MISSING,
                    "worldchunk-missing",
                    source,
                    "watched payload delta missing from WorldChunk",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread()
                            .getName(),
                    null
            );
        }
    }

    /**
     * Logs trace information when a delta is consumed in the WorldChunk constructor.
     *
     * @param chunk         the WorldChunk instance
     * @param expectedDelta the expected delta state, if any, may be null
     * @param operationId   active trace session operation ID, if any, may be null
     * @param source        class/method trace source trigger label
     */
    public static void traceWorldChunkConstructorConsumed(
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        PayloadWatchTracer.forEachResolvedWatchedBlockState(chunk, expectedDelta, operationId, match -> {
            final PayloadWatchTarget target = match.target();
            final BlockState expectedState = match.expectedState();
            final String resolvedOperationId = match.operationId();
            BlockWatchTraceTracker.markWorldConstructorConsumed(worldId, chunkPos, target, resolvedOperationId);
            PayloadWatchTracer.traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    "worldchunk-constructor",
                    source,
                    chunk
            );
            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_WORLD_CHUNK_CONSTRUCTOR_CONSUMED,
                    "worldchunk-constructor",
                    source,
                    "watched block delta consumed during WorldChunk construction",
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    PayloadWatchSummaries.summarizeExpectedAndActual(
                            target,
                            expectedState,
                            chunk.getBlockState(new BlockPos(target.blockX(), target.blockY(), target.blockZ())),
                            null,
                            chunk.getStatus(),
                            source,
                            Thread.currentThread()
                                    .getName(),
                            chunk
                    ),
                    null
            );
        });
    }

    /**
     * Traces the live state block matching of a chunk currently loaded in the world.
     *
     * @param chunk            the WorldChunk instance
     * @param presentEventType event type to log when block matches
     * @param stage            text label indicating the lifecycle stage name
     * @param source           class/method trace source trigger label
     * @param operationId      active trace session operation ID, may be null
     * @param expectedDelta    the expected delta, may be null
     */
    public static void traceLiveChunkState(
            final WorldChunk chunk,
            final ChunkTraceEventType presentEventType,
            final String stage,
            final String source,
            @Nullable final String operationId,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        final boolean clientChunk = chunk.getWorld()
                .isClient();

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                DebugChunkKeys.of(chunkPos)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }

            final BlockPos pos = new BlockPos(target.blockX(), target.blockY(), target.blockZ());
            final BlockState liveState = chunk.getBlockState(pos);
            final BlockState expectedState = PayloadWatchTracer.resolveExpectedState(chunk,
                    expectedDelta,
                    target,
                    chunkPos,
                    worldId);
            final String resolvedOperationId = PayloadWatchTracer.resolveOperationId(worldId,
                    chunkPos,
                    target,
                    operationId);
            PayloadWatchTracer.markVisibilitySeen(worldId, chunkPos, target, resolvedOperationId);
            PayloadWatchTracer.assertRestoreDecisionSeen(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    source
            );
            PayloadWatchTracer.traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    stage,
                    source,
                    chunk
            );
            PayloadWatchTracer.assertSameAppliedChunkInstance(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    source,
                    chunk
            );

            PayloadWatchTracer.traceWatch(
                    presentEventType,
                    stage,
                    source,
                    "watched block observed in live chunk",
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    PayloadWatchSummaries.summarizeExpectedAndActual(
                            target,
                            expectedState,
                            clientChunk ? null : liveState,
                            clientChunk ? liveState : null,
                            chunk.getStatus(),
                            source,
                            Thread.currentThread()
                                    .getName(),
                            chunk
                    ),
                    null
            );

            if (expectedState != null && !Objects.equals(liveState, expectedState)) {
                PayloadWatchTracer.traceWatch(
                        ChunkTraceEventType.WATCH_OVERWRITTEN_AFTER_RESTORE,
                        stage,
                        source,
                        "watched block differs from expected restored state",
                        worldId,
                        chunkPos,
                        resolvedOperationId,
                        target,
                        PayloadWatchSummaries.summarizeExpectedAndActual(
                                target,
                                expectedState,
                                clientChunk ? null : liveState,
                                clientChunk ? liveState : null,
                                chunk.getStatus(),
                                source,
                                Thread.currentThread()
                                        .getName(),
                                chunk
                        ),
                        null
                );
            }

            if (clientChunk && expectedState != null && Objects.equals(liveState, expectedState)) {
                PayloadWatchTracer.traceWatch(
                        ChunkTraceEventType.WATCH_CLIENT_SEES_EXPECTED_STATE,
                        stage,
                        source,
                        "client world sees expected watched block state",
                        worldId,
                        chunkPos,
                        resolvedOperationId,
                        target,
                        PayloadWatchSummaries.summarizeExpectedAndActual(
                                target,
                                expectedState,
                                null,
                                liveState,
                                chunk.getStatus(),
                                source,
                                Thread.currentThread()
                                        .getName(),
                                chunk
                        ),
                        null
                );
            }
        }
    }
}
