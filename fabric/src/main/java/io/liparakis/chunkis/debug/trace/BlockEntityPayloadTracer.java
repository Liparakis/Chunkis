package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic payload tracer for block entity storage capture and restore lifecycles.
 */
public final class BlockEntityPayloadTracer {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private BlockEntityPayloadTracer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Logs trace information when a block entity state has been captured.
     *
     * @param worldId     target world dimension registry ID string
     * @param chunkPos    coordinates of the enclosing chunk
     * @param pos         coordinates of the block entity
     * @param blockEntity block entity instance, may be null
     * @param nbt         the captured block entity NBT compound, may be null
     */
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

    /**
     * Logs trace information when capturing a block entity state has been skipped.
     *
     * @param world    target world instance
     * @param chunkPos coordinates of the enclosing chunk
     * @param pos      coordinates of the block entity
     * @param message  reason why capture was skipped
     */
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

    /**
     * Logs trace information when a block entity has been restored to the live world.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param pos         coordinates of the block entity
     * @param blockEntity restored block entity instance
     * @param nbt         source compound NBT
     * @param operationId active restore operation ID
     */
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

    /**
     * Logs trace information when restoring a block entity has been skipped.
     *
     * @param world       target world instance
     * @param chunkPos    coordinates of the enclosing chunk
     * @param pos         coordinates of the block entity
     * @param operationId active restore operation ID
     * @param message     reason why restore was skipped
     */
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

    /**
     * Logs whether watched block entities are present inside a vanilla chunk-NBT root.
     *
     * <p>This is trace-only instrumentation for narrowing whether a watched block
     * entity is lost before or after the persisted base chunk root is built.</p>
     */
    public static void traceBlockEntityPresenceInChunkNbt(
            final String worldId,
            final ChunkPos chunkPos,
            @Nullable final String operationId,
            @Nullable final NbtCompound chunkNbt,
            final String stage,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || chunkNbt == null) {
            return;
        }

        final NbtList blockEntities = chunkNbt.getList("block_entities")
                .orElse(null);
        final int blockEntityCount = blockEntities != null ? blockEntities.size() : 0;
        final int sectionCount = chunkNbt.getList("sections")
                .map(NbtList::size)
                .orElse(0);
        final Object status = chunkNbt.getString("Status")
                .orElse("<missing>");

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                io.liparakis.chunkis.debug.util.DebugChunkKeys.of(chunkPos)
        )) {
            if (!target.hasBlockCoordinates()) {
                continue;
            }

            final NbtCompound matched = findBlockEntityNbt(blockEntities, target);
            PayloadWatchTracer.traceWatch(
                    matched != null ? ChunkTraceEventType.WATCH_CAPTURED : ChunkTraceEventType.WATCH_FAILED,
                    stage,
                    source,
                    matched != null
                            ? "watched block entity present in chunk nbt root"
                            : "watched block entity missing from chunk nbt root",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                            + " chunkStatus=" + status
                            + " rootSectionCount=" + sectionCount
                            + " rootBlockEntityCount=" + blockEntityCount
                            + " matchedNbtId=" + (matched != null ? matched.getString("id")
                                                                    .orElse("<missing-id>")
                            : "<missing>")
                            + " matchedNbtBytes=" + PayloadWatchSummaries.nbtSize(matched),
                    null
            );
        }
    }

    /**
     * Performs find block entity nbt.
     */
    @Nullable
    private static NbtCompound findBlockEntityNbt(
            @Nullable final NbtList blockEntities,
            final PayloadWatchTarget target
    ) {
        if (blockEntities == null || !target.hasBlockCoordinates()) {
            return null;
        }
        for (final NbtElement element : blockEntities) {
            if (!(element instanceof NbtCompound compound)) {
                continue;
            }
            final int x = compound.getInt("x")
                    .orElse(Integer.MIN_VALUE);
            final int y = compound.getInt("y")
                    .orElse(Integer.MIN_VALUE);
            final int z = compound.getInt("z")
                    .orElse(Integer.MIN_VALUE);
            if (x == target.blockX() && y == target.blockY() && z == target.blockZ()) {
                return compound;
            }
        }
        return null;
    }
}
