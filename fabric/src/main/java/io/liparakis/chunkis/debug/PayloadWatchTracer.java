package io.liparakis.chunkis.debug;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class PayloadWatchTracer {

    private PayloadWatchTracer() {
        throw new AssertionError("Utility class");
    }

    public static void traceCapturedBlocks(final WorldChunk chunk) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK) {
                continue;
            }

            final BlockPos pos = new BlockPos(target.blockX(), target.blockY(), target.blockZ());
            final BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                traceWatch(
                        ChunkTraceEventType.WATCH_SKIPPED,
                        "capture",
                        "PayloadWatchTracer#traceCapturedBlocks",
                        "capture skipped: block is air",
                        worldId,
                        chunkPos,
                        null,
                        target,
                        summarizeBlock(target, state),
                        null
                );
                continue;
            }

            traceWatch(
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "capture",
                    "PayloadWatchTracer#traceCapturedBlocks",
                    "block captured",
                    worldId,
                    chunkPos,
                    null,
                    target,
                    summarizeBlock(target, state),
                    null
            );
        }
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

        final String summary = summarizeBlockEntity(target, blockEntity, nbt);
        if (blockEntity == null || nbt == null || nbt.isEmpty()) {
            traceWatch(
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

        traceWatch(
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
                worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }

        traceWatch(
                ChunkTraceEventType.WATCH_SKIPPED,
                "capture",
                "PayloadWatchTracer#traceSkippedBlockEntityCapture",
                message,
                worldId(world),
                chunkPos,
                null,
                target,
                "pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ(),
                null
        );
    }

    public static void traceCapturedEntities(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final List<NbtCompound> entities
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || entities.isEmpty()) {
            return;
        }

        for (final NbtCompound entityNbt : entities) {
            final String entityUuid = entityUuid(entityNbt);
            if (entityUuid == null) {
                continue;
            }

            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(worldId(world), entityUuid);
            if (target == null) {
                continue;
            }

            traceWatch(
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "capture",
                    "PayloadWatchTracer#traceCapturedEntities",
                    "entity captured",
                    worldId(world),
                    chunkPos,
                    null,
                    target,
                    summarizeEntity(target, entityNbt),
                    null
            );
        }
    }

    public static void traceDeltaStage(
            final String worldId,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final ChunkTraceEventType eventType,
            final String stage,
            final String source,
            final String message,
            final Integer byteSize
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        Objects.requireNonNull(delta, "delta");

        delta.forEachBlock((x, y, z, state) -> {
            final int worldX = chunkPos.getStartX() + x;
            final int worldZ = chunkPos.getStartZ() + z;
            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(worldId, worldX, y, worldZ);
            if (target != null) {
                traceWatch(
                        eventType,
                        stage,
                        source,
                        message,
                        worldId,
                        chunkPos,
                        operationId,
                        target,
                        summarizeBlock(target, state),
                        byteSize
                );
            }
        });

        delta.getBlockEntities().long2ObjectEntrySet().forEach(entry -> {
            final int x = io.liparakis.chunkis.core.BlockInstruction.unpackX(entry.getLongKey());
            final int y = io.liparakis.chunkis.core.BlockInstruction.unpackY(entry.getLongKey());
            final int z = io.liparakis.chunkis.core.BlockInstruction.unpackZ(entry.getLongKey());
            final int worldX = chunkPos.getStartX() + x;
            final int worldZ = chunkPos.getStartZ() + z;
            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(worldId, worldX, y, worldZ);
            if (target != null) {
                traceWatch(
                        eventType,
                        stage,
                        source,
                        message,
                        worldId,
                        chunkPos,
                        operationId,
                        target,
                        summarizeBlockEntity(target, null, entry.getValue()),
                        byteSize
                );
            }
        });

        delta.forEachEntity(entityNbt -> {
            if (entityNbt == null) {
                return;
            }
            final String entityUuid = entityUuid(entityNbt);
            if (entityUuid == null) {
                return;
            }
            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(worldId, entityUuid);
            if (target != null) {
                traceWatch(
                        eventType,
                        stage,
                        source,
                        message,
                        worldId,
                        chunkPos,
                        operationId,
                        target,
                        summarizeEntity(target, entityNbt),
                        byteSize
                );
            }
        });
    }

    public static void traceDecodeOutcome(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final boolean storageEntryPresent
    ) {
        final String worldId = worldId(world);
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        if (storageEntryPresent) {
            traceDeltaStage(
                    worldId,
                    chunkPos,
                    delta,
                    operationId,
                    ChunkTraceEventType.WATCH_STORAGE_READ,
                    "storage-read",
                    "PayloadWatchTracer#traceDecodeOutcome",
                    "payload bytes read from storage",
                    null
            );
        }

        traceDeltaStage(
                worldId,
                chunkPos,
                delta,
                operationId,
                ChunkTraceEventType.WATCH_DECODED,
                "decode",
                "PayloadWatchTracer#traceDecodeOutcome",
                "payload decoded",
                null
        );

        if (!storageEntryPresent) {
            return;
        }

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() == PayloadWatchType.ENTITY) {
                continue;
            }
            if (!contains(delta, target, chunkPos, worldId)) {
                traceWatch(
                        ChunkTraceEventType.WATCH_FAILED,
                        "decode",
                        "PayloadWatchTracer#traceDecodeOutcome",
                        "missing after decode",
                        worldId,
                        chunkPos,
                        operationId,
                        target,
                        target.describe(),
                        null
                );
            }
        }
    }

    public static void traceRestoreStarted(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId
    ) {
        traceDeltaStage(
                worldId(world),
                chunkPos,
                delta,
                operationId,
                ChunkTraceEventType.WATCH_RESTORE_STARTED,
                "restore-start",
                "PayloadWatchTracer#traceRestoreStarted",
                "payload entered restore",
                null
        );
    }

    public static void traceRestoredBlock(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final BlockPos pos,
            final BlockState state,
            final String operationId
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(
                worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_RESTORED,
                "restore",
                "PayloadWatchTracer#traceRestoredBlock",
                "block restored to live world",
                worldId(world),
                chunkPos,
                operationId,
                target,
                summarizeBlock(target, state),
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
                worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_FAILED,
                "restore",
                "PayloadWatchTracer#traceRestoreBlockFailure",
                message,
                worldId(world),
                chunkPos,
                operationId,
                target,
                target.describe(),
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
                worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_RESTORED,
                "restore",
                "PayloadWatchTracer#traceRestoredBlockEntity",
                "block entity restored to live world",
                worldId(world),
                chunkPos,
                operationId,
                target,
                summarizeBlockEntity(target, blockEntity, nbt),
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
                worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_SKIPPED,
                "restore",
                "PayloadWatchTracer#traceRestoreBlockEntitySkipped",
                message,
                worldId(world),
                chunkPos,
                operationId,
                target,
                target.describe(),
                null
        );
    }

    public static void traceRestoredEntity(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final Entity entity,
            final NbtCompound nbt,
            final String operationId
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                worldId(world),
                entity.getUuidAsString()
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_RESTORED,
                "restore",
                "PayloadWatchTracer#traceRestoredEntity",
                "entity restored to live world",
                worldId(world),
                chunkPos,
                operationId,
                target,
                summarizeEntity(target, nbt),
                null
        );
    }

    public static void traceRestoreEntitySkipped(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final String entityUuid,
            final String operationId,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                worldId(world),
                entityUuid
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_SKIPPED,
                "restore",
                "PayloadWatchTracer#traceRestoreEntitySkipped",
                message,
                worldId(world),
                chunkPos,
                operationId,
                target,
                target.describe(),
                null
        );
    }

    private static boolean contains(
            final ChunkDelta<BlockState, NbtCompound> delta,
            final PayloadWatchTarget target,
            final ChunkPos chunkPos,
            final String worldId
    ) {
        final boolean[] found = {false};

        if (target.type() == PayloadWatchType.BLOCK) {
            delta.forEachBlock((x, y, z, state) -> {
                if (found[0]) {
                    return;
                }
                final int worldX = chunkPos.getStartX() + x;
                final int worldZ = chunkPos.getStartZ() + z;
                found[0] = target.matchesBlock(worldId, PayloadWatchType.BLOCK, worldX, y, worldZ);
            });
            return found[0];
        }

        if (target.type() == PayloadWatchType.BLOCK_ENTITY) {
            delta.getBlockEntities().long2ObjectEntrySet().forEach(entry -> {
                if (found[0]) {
                    return;
                }
                final int x = io.liparakis.chunkis.core.BlockInstruction.unpackX(entry.getLongKey());
                final int y = io.liparakis.chunkis.core.BlockInstruction.unpackY(entry.getLongKey());
                final int z = io.liparakis.chunkis.core.BlockInstruction.unpackZ(entry.getLongKey());
                final int worldX = chunkPos.getStartX() + x;
                final int worldZ = chunkPos.getStartZ() + z;
                found[0] = target.matchesBlock(worldId, PayloadWatchType.BLOCK_ENTITY, worldX, y, worldZ);
            });
            return found[0];
        }

        delta.forEachEntity(entityNbt -> {
            if (found[0] || entityNbt == null) {
                return;
            }
            final String uuid = entityUuid(entityNbt);
            found[0] = uuid != null && target.matchesEntity(worldId, uuid);
        });
        return found[0];
    }

    private static void traceWatch(
            final ChunkTraceEventType eventType,
            final String stage,
            final String source,
            final String message,
            final String worldId,
            final ChunkPos chunkPos,
            final String operationId,
            final PayloadWatchTarget target,
            final String summary,
            final Integer byteSize
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                eventType,
                eventType == ChunkTraceEventType.WATCH_FAILED ? ChunkTraceSeverity.ERROR : ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                source,
                message,
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                null,
                byteSize,
                target,
                stage,
                summary
        );
    }

    private static String summarizeBlock(final PayloadWatchTarget target, final BlockState state) {
        return "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                + " state=" + state
                + " section=" + (target.blockY() >> 4);
    }

    private static String summarizeBlockEntity(
            final PayloadWatchTarget target,
            @Nullable final BlockEntity blockEntity,
            @Nullable final NbtCompound nbt
    ) {
        final String type = blockEntity != null
                ? String.valueOf(net.minecraft.block.entity.BlockEntityType.getId(blockEntity.getType()))
                : nbt != null ? nbt.getString("id").orElse("<missing-id>") : "<missing>";
        return "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                + " type=" + type
                + " nbtBytes=" + nbtSize(nbt);
    }

    private static String summarizeEntity(
            final PayloadWatchTarget target,
            final NbtCompound nbt
    ) {
        return "uuid=" + target.entityUuid()
                + " type=" + nbt.getString("id").orElse("<missing-id>")
                + " pos=" + nbt.getList("Pos").map(Object::toString).orElse("[]")
                + " nbtBytes=" + nbtSize(nbt);
    }

    private static int nbtSize(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return 0;
        }
        try {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                net.minecraft.nbt.NbtIo.writeCompound(nbt, output);
            }
            return buffer.size();
        } catch (final IOException ignored) {
            return nbt.toString().length();
        }
    }

    @Nullable
    private static String entityUuid(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return null;
        }
        return nbt.getIntArray("UUID")
                .map(net.minecraft.util.Uuids::toUuid)
                .map(java.util.UUID::toString)
                .orElse(null);
    }

    private static String worldId(final ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    private static String worldId(final WorldChunk chunk) {
        return chunk.getWorld().getRegistryKey().getValue().toString();
    }
}
