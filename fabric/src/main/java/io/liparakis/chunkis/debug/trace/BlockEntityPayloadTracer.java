package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

public final class BlockEntityPayloadTracer {

    private BlockEntityPayloadTracer() {
        throw new AssertionError("Utility class");
    }

    public static void traceCapturedBlockEntity(
            final String worldId,
            final ChunkPos chunkPos,
            final BlockPos pos,
            @Nullable final BlockEntity blockEntity,
            @Nullable final NbtCompound nbt
                                               ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                worldId,
                pos.getX(),
                pos.getY(),
                pos.getZ()
                                                                                  );
        if (target == null) {
            return;
        }

        final String summary = PayloadWatchSummaries.summarizeBlockEntity(target, blockEntity, nbt);
        if (blockEntity == null || nbt == null || nbt.isEmpty()) {
            PayloadWatchTracer.traceWatch(
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "capture",
                    "PayloadWatchTracer#traceCapturedBlockEntity",
                    "capture skipped: block entity NBT missing",
                    worldId,
                    chunkPos,
                    null,
                    target,
                    summary,
                    null
                                         );
            return;
        }

        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_CAPTURED,
                "capture",
                "PayloadWatchTracer#traceCapturedBlockEntity",
                "block entity captured",
                worldId,
                chunkPos,
                null,
                target,
                summary,
                null
                                     );
    }

    public static void traceSkippedBlockEntityCapture(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final BlockPos pos,
            final String message
                                                     ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                PayloadWatchSummaries.worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
                                                                                  );
        if (target == null) {
            return;
        }

        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_SKIPPED,
                "capture",
                "PayloadWatchTracer#traceSkippedBlockEntityCapture",
                message,
                PayloadWatchSummaries.worldId(world),
                chunkPos,
                null,
                target,
                "pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ(),
                null
                                     );
    }

    public static void traceRestoredBlockEntity(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final BlockPos pos,
            final BlockEntity blockEntity,
            final NbtCompound nbt,
            final String operationId
                                               ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                PayloadWatchSummaries.worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
                                                                                  );
        if (target == null) {
            return;
        }
        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_RESTORE_APPLIED,
                "restore",
                "PayloadWatchTracer#traceRestoredBlockEntity",
                "block entity restored to live world",
                PayloadWatchSummaries.worldId(world),
                chunkPos,
                operationId,
                target,
                PayloadWatchSummaries.summarizeBlockEntity(target, blockEntity, nbt),
                null
                                     );
    }

    public static void traceRestoreBlockEntitySkipped(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final BlockPos pos,
            final String operationId,
            final String message
                                                     ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                PayloadWatchSummaries.worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
                                                                                  );
        if (target == null) {
            return;
        }
        PayloadWatchTracer.traceWatch(
                ChunkTraceEventType.WATCH_SKIPPED,
                "restore",
                "PayloadWatchTracer#traceRestoreBlockEntitySkipped",
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
