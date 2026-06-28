package io.liparakis.chunkis.debug;

import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Formatting and tiny lookup helpers for payload watch trace summaries.
 *
 * <p>This keeps string assembly and small NBT/entity identity probes out of
 * {@link PayloadWatchTracer}, which should stay focused on deciding when to
 * emit trace events.</p>
 */
final class PayloadWatchSummaries {

    private PayloadWatchSummaries() {
        throw new AssertionError("Utility class");
    }

    static String summarizeBlock(final PayloadWatchTarget target, final BlockState state) {
        if (!target.hasBlockCoordinates()) {
            return target.describe() + " state=" + state;
        }
        return "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                + " state=" + state
                + " section=" + (target.blockY() >> 4);
    }

    static String summarizeExpectedAndActual(
            final PayloadWatchTarget target,
            @Nullable final BlockState expectedState,
            @Nullable final BlockState actualServerState,
            @Nullable final BlockState actualClientState,
            final Object chunkStatus,
            final String source,
            final String threadName,
            @Nullable final WorldChunk chunk
    ) {
        if (!target.hasBlockCoordinates()) {
            return target.describe()
                    + " expectedState=" + expectedState
                    + " actualServerState=" + actualServerState
                    + " actualClientState=" + actualClientState
                    + " chunkInstanceId=" + chunkInstanceId(chunk)
                    + " chunkStatus=" + chunkStatus
                    + " source=" + source
                    + " thread=" + threadName;
        }
        return "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                + " expectedState=" + expectedState
                + " actualServerState=" + actualServerState
                + " actualClientState=" + actualClientState
                + " chunkInstanceId=" + chunkInstanceId(chunk)
                + " chunkStatus=" + chunkStatus
                + " source=" + source
                + " thread=" + threadName
                + " section=" + (target.blockY() >> 4);
    }

    static String summarizeBlockEntity(
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

    static String summarizeEntity(
            final PayloadWatchTarget target,
            final NbtCompound nbt
    ) {
        return "uuid=" + target.entityUuid()
                + " type=" + nbt.getString("id").orElse("<missing-id>")
                + " pos=" + nbt.getList("Pos").map(Object::toString).orElse("[]")
                + " nbtBytes=" + nbtSize(nbt);
    }

    static String summarizeExpectedEntityAndPresence(
            final PayloadWatchTarget target,
            @Nullable final NbtCompound expectedNbt,
            @Nullable final Entity liveEntity,
            final WorldChunk chunk
    ) {
        return "uuid=" + target.entityUuid()
                + " expectedType=" + (expectedNbt != null ? expectedNbt.getString("id").orElse("<missing-id>") : "<unknown>")
                + " actualServerEntityPresent=" + (liveEntity != null)
                + " actualServerEntityType=" + (liveEntity != null ? liveEntity.getType() : "<missing>")
                + " chunkStatus=" + chunk.getStatus()
                + " chunkInstanceId=" + chunkInstanceId(chunk)
                + " thread=" + Thread.currentThread().getName();
    }

    static String summarizeRemovedEntity(
            final PayloadWatchTarget target,
            final Entity entity,
            final Entity.RemovalReason reason,
            final String source
    ) {
        return "uuid=" + target.entityUuid()
                + " actualServerEntityPresent=false"
                + " actualServerEntityType=" + entity.getType()
                + " removalReason=" + reason
                + " pos=[" + entity.getX() + ',' + entity.getY() + ',' + entity.getZ() + ']'
                + " blockPos=" + entity.getBlockPos().toShortString()
                + " chunk=" + entity.getChunkPos().x + ',' + entity.getChunkPos().z
                + " isAlive=" + entity.isAlive()
                + " isRemoved=" + entity.isRemoved()
                + " source=" + source
                + " thread=" + Thread.currentThread().getName();
    }

    static String summarizeLiveEntity(
            final PayloadWatchTarget target,
            final Entity entity,
            final String source
    ) {
        return "uuid=" + target.entityUuid()
                + " actualServerEntityPresent=" + !entity.isRemoved()
                + " actualServerEntityType=" + entity.getType()
                + " pos=[" + entity.getX() + ',' + entity.getY() + ',' + entity.getZ() + ']'
                + " blockPos=" + entity.getBlockPos().toShortString()
                + " chunk=" + entity.getChunkPos().x + ',' + entity.getChunkPos().z
                + " isAlive=" + entity.isAlive()
                + " isRemoved=" + entity.isRemoved()
                + " source=" + source
                + " thread=" + Thread.currentThread().getName();
    }

    static int nbtSize(@Nullable final NbtCompound nbt) {
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

    static @Nullable String entityUuid(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return null;
        }
        return nbt.getIntArray("UUID")
                .map(net.minecraft.util.Uuids::toUuid)
                .map(UUID::toString)
                .orElse(null);
    }

    static String worldId(final ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    static String worldId(final WorldChunk chunk) {
        return chunk.getWorld().getRegistryKey().getValue().toString();
    }

    static String chunkInstanceId(@Nullable final Chunk chunk) {
        return chunk != null
                ? chunk.getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(chunk))
                : "UNAVAILABLE";
    }
}
