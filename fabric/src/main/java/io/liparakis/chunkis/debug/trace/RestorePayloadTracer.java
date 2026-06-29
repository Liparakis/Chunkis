package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic payload tracer tracking blocks restore, verification write status, and skips.
 */
public final class RestorePayloadTracer {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private RestorePayloadTracer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Logs trace information when a chunk restore process starts.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the chunk
     * @param chunk       the loaded world chunk instance
     * @param delta       the restoring chunk delta state
     * @param operationId active restore session operation ID
     */
    public static void traceRestoreStarted(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(world);
        PayloadWatchTracer.forEachWatchedBlockState(worldId, chunkPos, delta, match -> {
            final PayloadWatchTarget target = match.target();
            final BlockState expectedState = match.expectedState();
            final String resolvedOperationId = PayloadWatchTracer.resolveOperationId(worldId,
                    chunkPos,
                    target,
                    operationId);
            final BlockPos pos = new BlockPos(target.blockX(), target.blockY(), target.blockZ());
            final BlockState actualState = chunk.getBlockState(pos);
            PayloadWatchTracer.markVisibilitySeen(worldId, chunkPos, target, resolvedOperationId);
            PayloadWatchTracer.traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    "restore-start",
                    "ChunkRestorer#restore",
                    chunk
            );
            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_RESTORE_STARTED,
                    "restore-start",
                    "PayloadWatchTracer#traceRestoreStarted",
                    "payload entered restore",
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    PayloadWatchSummaries.summarizeExpectedAndActual(
                            target,
                            expectedState,
                            actualState,
                            null,
                            chunk.getStatus(),
                            "ChunkRestorer#restore",
                            Thread.currentThread()
                                    .getName(),
                            chunk
                    ),
                    null
            );
        });
    }

    /**
     * Logs trace information when a block position has been visited during restore phase.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreInstructionVisited(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source
    ) {
        PayloadWatchTracer.traceRestoreMutationStage(
                ChunkTraceEventType.WATCH_RESTORE_INSTRUCTION_VISITED,
                "restore-instruction-visited",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                null
        );
    }

    /**
     * Logs trace information showing that a block restoration write is being attempted.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreApplyAttempt(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source
    ) {
        PayloadWatchTracer.traceRestoreMutationStage(
                ChunkTraceEventType.WATCH_RESTORE_APPLY_ATTEMPT,
                "restore-apply-attempt",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                null
        );
    }

    /**
     * Logs trace information logging values returned after restoring block states.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreSetBlockReturned(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source
    ) {
        PayloadWatchTracer.traceRestoreSetBlockReturned(
                ChunkTraceEventType.WATCH_RESTORE_SETBLOCK_RETURNED,
                "restore-setblock-returned",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                null
        );
    }

    /**
     * Logs trace information evaluating post-write block values and asserting correct execution.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreStateAfterSetBlock(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source
    ) {
        PayloadWatchTracer.traceRestoreMutationStage(
                ChunkTraceEventType.WATCH_RESTORE_STATE_AFTER_SETBLOCK,
                "restore-state-after-setblock",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                null
        );
        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final PayloadWatchTarget target = PayloadWatchTracer.watchedBlockTarget(worldId, pos);
        if (target == null || expectedState == null || Objects.equals(chunk.getBlockState(pos), expectedState)) {
            return;
        }
        io.liparakis.chunkis.debug.trace.ChunkTraceStore.trace(
                io.liparakis.chunkis.debug.model.ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                io.liparakis.chunkis.debug.model.ChunkTraceSeverity.ERROR,
                io.liparakis.chunkis.debug.model.ChunkTraceReason.RESTORE_SETBLOCK_DID_NOT_APPLY,
                source,
                "restore write did not produce expected immediate live block state",
                worldId,
                DebugChunkKeys.of(chunk.getPos()),
                null,
                operationId,
                null,
                null
        );
    }

    /**
     * Logs trace information when block application failed.
     *
     * @param chunk         target world chunk
     * @param pos           block coordinate pos
     * @param previousState block state prior to application, may be null
     * @param expectedState target block state to write, may be null
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     * @param reason        detailed failure classification text
     */
    public static void traceRestoreApplyFailed(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source,
            final String reason
    ) {
        PayloadWatchTracer.traceRestoreMutationStage(
                ChunkTraceEventType.WATCH_RESTORE_APPLY_FAILED,
                "restore-apply-failed",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                reason
        );
    }

    /**
     * Logs trace information when block restoration is skipped due to matching live states.
     *
     * @param chunk         target world chunk
     * @param expectedDelta chunk delta container containing expected block updates
     * @param operationId   active restore session operation ID
     * @param source        class/method trace source label
     */
    public static void traceRestoreSkipped(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            final String operationId,
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
            if (PayloadWatchTracer.isRestoreDecisionSeen(worldId, chunkPos, target, resolvedOperationId)) {
                return;
            }
            PayloadWatchTracer.markRestoreDecisionSeen(worldId, chunkPos, target, resolvedOperationId);
            PayloadWatchTracer.markVisibilitySeen(worldId, chunkPos, target, resolvedOperationId);
            PayloadWatchTracer.traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    "restore-skipped",
                    source,
                    chunk
            );
            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_RESTORE_SKIPPED,
                    "restore-skipped",
                    source,
                    "decoded watched block never reached restore apply",
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
     * Logs trace information when a block is successfully written to the world state.
     *
     * @param chunk       target world chunk
     * @param pos         block coordinate pos
     * @param state       applied block state
     * @param operationId active restore session operation ID
     */
    public static void traceRestoredBlock(
            final WorldChunk chunk,
            final BlockPos pos,
            final BlockState state,
            final String operationId
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final PayloadWatchTarget target = PayloadWatchTracer.watchedBlockTarget(worldId, pos);
        if (target == null) {
            return;
        }
        final BlockState actualState = chunk.getBlockState(pos);
        final String resolvedOperationId = PayloadWatchTracer.resolveOperationId(worldId,
                chunk.getPos(),
                target,
                operationId);
        PayloadWatchTracer.markRestoreDecisionSeen(worldId, chunk.getPos(), target, resolvedOperationId);
        PayloadWatchTracer.markVisibilitySeen(worldId, chunk.getPos(), target, resolvedOperationId);
        PayloadWatchTracer.recordAppliedChunkInstance(worldId, chunk.getPos(), target, resolvedOperationId, chunk);
        PayloadWatchTracer.traceChunkIdentityAndStatus(
                worldId,
                chunk.getPos(),
                target,
                resolvedOperationId,
                "restore-applied",
                "ChunkRestorer.RestorationVisitor#visitBlock",
                chunk
        );
        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_RESTORE_APPLIED,
                "restore-applied",
                "PayloadWatchTracer#traceRestoredBlock",
                "block restored to live world",
                worldId,
                chunk.getPos(),
                resolvedOperationId,
                target,
                PayloadWatchSummaries.summarizeExpectedAndActual(
                        target,
                        state,
                        actualState,
                        null,
                        chunk.getStatus(),
                        "ChunkRestorer.RestorationVisitor#visitBlock",
                        Thread.currentThread()
                                .getName(),
                        chunk
                ),
                null
        );
    }

    /**
     * Logs trace information when restoring block failed.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param pos         coordinates of the block entity
     * @param operationId active restore operation ID
     * @param message     description detail text
     */
    public static void traceRestoreBlockFailure(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final BlockPos pos,
            final String operationId,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(
                PayloadWatchSummaries.worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_FAILED,
                "restore",
                "PayloadWatchTracer#traceRestoreBlockFailure",
                message,
                PayloadWatchSummaries.worldId(world),
                chunkPos,
                operationId,
                target,
                target.describe(),
                null
        );
    }
}
