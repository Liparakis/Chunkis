package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchType;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

public final class BlockPayloadTracer {

    private BlockPayloadTracer() {
        throw new AssertionError("Utility class");
    }

    public static void traceBlockSetStateEntered(
            final WorldChunk chunk,
            final BlockPos pos,
            final BlockState previous,
            final BlockState next,
            final int flags,
            final String caller,
            final ChunkMutationTrackingScope.Cause passiveCause,
            final boolean mutationSuppressed,
            final boolean mutationAccepted,
            final boolean deltaCreated,
            final int blockChangesBefore,
            final int blockChangesAfter,
            final long mutationGeneration,
            @Nullable final String message
                                                ) {
        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final PayloadWatchTarget target = PayloadWatchTracer.watchedBlockTarget(worldId, pos);
        if (target == null) {
            return;
        }

        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_BLOCK_SETSTATE_ENTERED,
                "mutation",
                caller,
                message != null ? message : "setBlockState entered",
                worldId,
                chunk.getPos(),
                null,
                target,
                "pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ()
                        + " oldState=" + previous
                        + " newState=" + next
                        + " flags=" + flags
                        + " caller=" + caller
                        + " thread=" + Thread.currentThread().getName()
                        + " classification=" + PayloadWatchTracer.classifySetBlockStateEvent(chunk)
                        + " authoritative=" + !chunk.getWorld().isClient()
                        + " passiveContext=" + passiveCause
                        + " mutationSuppressed=" + mutationSuppressed
                        + " mutationAccepted=" + mutationAccepted
                        + " deltaCreated=" + deltaCreated
                        + " blockChangesBefore=" + blockChangesBefore
                        + " blockChangesAfter=" + blockChangesAfter
                        + " mutationGeneration=" + mutationGeneration,
                null
                                     );
    }

    public static void traceCapturedBlocks(final WorldChunk chunk) {
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

            final BlockPos pos = new BlockPos(target.blockX(), target.blockY(), target.blockZ());
            final BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                PayloadWatchTracer.traceWatch(
                        ChunkTraceEventType.WATCH_SKIPPED,
                        "capture",
                        "PayloadWatchTracer#traceCapturedBlocks",
                        "capture skipped: block is air",
                        worldId,
                        chunkPos,
                        null,
                        target,
                        PayloadWatchSummaries.summarizeBlock(target, state),
                        null
                                             );
                continue;
            }

            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "capture",
                    "PayloadWatchTracer#traceCapturedBlocks",
                    "block captured",
                    worldId,
                    chunkPos,
                    null,
                    target,
                    PayloadWatchSummaries.summarizeBlock(target, state),
                    null
                                         );
        }
    }
}
