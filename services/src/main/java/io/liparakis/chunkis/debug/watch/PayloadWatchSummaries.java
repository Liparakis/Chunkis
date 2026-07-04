package io.liparakis.chunkis.debug.watch;

import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Formatting and tiny lookup helpers for payload watch trace summaries.
 *
 * <p>This keeps string assembly and small NBT/entity identity probes out of
 * PayloadWatchTracer, which should stay focused on deciding when to
 * emit trace events.</p>
 */
public final class PayloadWatchSummaries {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private PayloadWatchSummaries() {
        throw new AssertionError("Utility class");
    }

    /**
     * Summarizes block watch target details and active states into string descriptors.
     *
     * @param target watchpoint target
     * @param state  active BlockState to print
     * @return summary string descriptor
     */
    public static String summarizeBlock(final PayloadWatchTarget target, final BlockState state) {
        if (!target.hasBlockCoordinates()) {
            return target.describe() + " state=" + state;
        }
        return "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                + " state=" + state
                + " section=" + (target.blockY() >> 4);
    }

    /**
     * Summarizes expected vs actual block state variations.
     *
     * @param target            watchpoint target
     * @param expectedState     expected block state, if any, may be null
     * @param actualServerState actual server block state, if any, may be null
     * @param actualClientState actual client block state, if any, may be null
     * @param chunkStatus       chunk status details
     * @param source            class/method trace source trigger label
     * @param threadName        active executing thread name
     * @param chunk             enclosing chunk instance, if any, may be null
     * @return summary string descriptor
     */
    public static String summarizeExpectedAndActual(
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

    /**
     * Summarizes block entity parameters and size characteristics.
     *
     * @param target      watchpoint target
     * @param blockEntity block entity instance, if any, may be null
     * @param nbt         block entity source NBT compound, if any, may be null
     * @return summary string descriptor
     */
    public static String summarizeBlockEntity(
            final PayloadWatchTarget target,
            @Nullable final BlockEntity blockEntity,
            @Nullable final NbtCompound nbt
    ) {
        final String type = blockEntity != null
                ? String.valueOf(net.minecraft.block.entity.BlockEntityType.getId(blockEntity.getType()))
                : nbt != null ? nbt.getString("id")
                                .orElse("<missing-id>") : "<missing>";
        return "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                + " type=" + type
                + " nbtBytes=" + nbtSize(nbt);
    }

    /**
     * Summarizes entity identification properties.
     *
     * @param target watchpoint target
     * @param nbt    entity source NBT compound
     * @return summary string descriptor
     */
    public static String summarizeEntity(
            final PayloadWatchTarget target,
            final NbtCompound nbt
    ) {
        return "uuid=" + target.entityUuid()
                + " type=" + nbt.getString("id")
                .orElse("<missing-id>")
                + " pos=" + nbt.getList("Pos")
                .map(Object::toString)
                .orElse("[]")
                + " nbtBytes=" + nbtSize(nbt);
    }

    /**
     * Summarizes expected entity properties vs loaded instances details.
     *
     * @param target      watchpoint target
     * @param expectedNbt expected entity NBT, if any, may be null
     * @param liveEntity  live entity instance, if any, may be null
     * @param chunk       enclosing chunk instance
     * @return summary string descriptor
     */
    public static String summarizeExpectedEntityAndPresence(
            final PayloadWatchTarget target,
            @Nullable final NbtCompound expectedNbt,
            @Nullable final Entity liveEntity,
            final WorldChunk chunk
    ) {
        return "uuid=" + target.entityUuid()
                + " expectedType=" + (expectedNbt != null ? expectedNbt.getString("id")
                                                            .orElse("<missing-id>")
                : "<unknown>")
                + " actualServerEntityPresent=" + (liveEntity != null)
                + " actualServerEntityType=" + (liveEntity != null ? liveEntity.getType() : "<missing>")
                + " chunkStatus=" + chunk.getStatus()
                + " chunkInstanceId=" + chunkInstanceId(chunk)
                + " thread=" + Thread.currentThread()
                .getName();
    }

    /**
     * Summarizes parameters for a removed server entity.
     *
     * @param target watchpoint target
     * @param entity removed entity instance
     * @param reason removal reason code
     * @param source class/method trace source trigger label
     * @return summary string descriptor
     */
    public static String summarizeRemovedEntity(
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
                + " blockPos=" + entity.getBlockPos()
                .toShortString()
                + " chunk=" + entity.getChunkPos().x + ',' + entity.getChunkPos().z
                + " isAlive=" + entity.isAlive()
                + " isRemoved=" + entity.isRemoved()
                + " source=" + source
                + " thread=" + Thread.currentThread()
                .getName();
    }

    /**
     * Summarizes parameters for an active live entity.
     *
     * @param target watchpoint target
     * @param entity live entity instance
     * @param source class/method trace source trigger label
     * @return summary string descriptor
     */
    public static String summarizeLiveEntity(
            final PayloadWatchTarget target,
            final Entity entity,
            final String source
    ) {
        return "uuid=" + target.entityUuid()
                + " actualServerEntityPresent=" + !entity.isRemoved()
                + " actualServerEntityType=" + entity.getType()
                + " pos=[" + entity.getX() + ',' + entity.getY() + ',' + entity.getZ() + ']'
                + " blockPos=" + entity.getBlockPos()
                .toShortString()
                + " chunk=" + entity.getChunkPos().x + ',' + entity.getChunkPos().z
                + " isAlive=" + entity.isAlive()
                + " isRemoved=" + entity.isRemoved()
                + " source=" + source
                + " thread=" + Thread.currentThread()
                .getName();
    }

    /**
     * Estimates NBT compound serialized data size in bytes.
     *
     * @param nbt source compound NBT, if any, may be null
     * @return estimated byte count
     */
    public static int nbtSize(@Nullable final NbtCompound nbt) {
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
            return nbt.toString()
                    .length();
        }
    }

    /**
     * Resolves the entity UUID string from compound NBT data.
     *
     * @param nbt source entity NBT compound, if any, may be null
     * @return resolved UUID string if found, or null
     */
    public static @Nullable String entityUuid(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return null;
        }
        return nbt.getIntArray("UUID")
                .map(net.minecraft.util.Uuids::toUuid)
                .map(UUID::toString)
                .orElse(null);
    }

    /**
     * Resolves world registry path string for a ServerWorld instance.
     *
     * @param world target server world
     * @return registry path identifier string
     */
    public static String worldId(final ServerWorld world) {
        return world.getRegistryKey()
                .getValue()
                .toString();
    }

    /**
     * Resolves world registry path string for a WorldChunk instance.
     *
     * @param chunk target world chunk
     * @return registry path identifier string
     */
    public static String worldId(final WorldChunk chunk) {
        return chunk.getWorld()
                .getRegistryKey()
                .getValue()
                .toString();
    }

    /**
     * Resolves a unique string identification representing the Chunk memory instance.
     *
     * @param chunk target chunk instance, if any, may be null
     * @return unique string identifier
     */
    public static String chunkInstanceId(@Nullable final Chunk chunk) {
        return chunk != null
                ? chunk.getClass()
                  .getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(chunk))
                : "UNAVAILABLE";
    }
}
