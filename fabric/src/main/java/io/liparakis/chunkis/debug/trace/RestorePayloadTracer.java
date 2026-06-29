package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchType;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public final class RestorePayloadTracer {
    private RestorePayloadTracer() {
        throw new AssertionError("Utility class");
    }

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
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = PayloadWatchTracer.findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            final String resolvedOperationId = PayloadWatchTracer.resolveOperationId(worldId, chunkPos, target, operationId);
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
                            Thread.currentThread().getName(),
                            chunk
                    ),
                    null
            );
        }
    }

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
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(worldId, pos.getX(), pos.getY(),
                pos.getZ());
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
                new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                null,
                operationId,
                null,
                null
        );
    }

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
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = PayloadWatchTracer.resolveExpectedState(chunk, expectedDelta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            final String resolvedOperationId = PayloadWatchTracer.resolveOperationId(worldId, chunkPos, target, operationId);
            if (PayloadWatchTracer.isRestoreDecisionSeen(worldId, chunkPos, target, resolvedOperationId)) {
                continue;
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
                            Thread.currentThread().getName(),
                            chunk
                    ),
                    null
            );
        }
    }

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
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(
                worldId,
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        final BlockState actualState = chunk.getBlockState(pos);
        final String resolvedOperationId = PayloadWatchTracer.resolveOperationId(worldId, chunk.getPos(), target, operationId);
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
                        Thread.currentThread().getName(),
                        chunk
                ),
                null
        );
    }

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
