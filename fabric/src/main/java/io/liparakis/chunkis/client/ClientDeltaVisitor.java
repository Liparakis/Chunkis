package io.liparakis.chunkis.client;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

/**
 * Reusable visitor that applies a decoded {@link ChunkDelta} to the client world.
 *
 * <p>
 * An instance is created once per thread via {@link ThreadLocal} in
 * {@link ClientDeltaNetworking} and recycled across packets via {@link #reset}.
 * This eliminates the per-packet allocation of visitor state (~80 bytes) and
 * the mutable {@link BlockPos} (~40 bytes per block change).
 *
 * <p>
 * <b>Thread safety:</b> Not thread-safe by design - must only be used from
 * the thread that created it (guaranteed by the {@link ThreadLocal} wrapper).
 *
 * <p>
 * No chunk or world references are retained beyond the scope of the
 * {@link #reset} call that provided them and the {@link ChunkDelta.DeltaVisitor}
 * methods that consume them.
 *
 */
@Environment(EnvType.CLIENT)
final class ClientDeltaVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {

    /**
     * Pre-allocated mutable position reused for every {@link #visitBlock} call,
     * avoiding per-block {@link BlockPos} allocation.
     */
    private final BlockPos.Mutable mutablePos = new BlockPos.Mutable();

    // Mutable context - set by reset() before each delta application.
    /** Stores block state. */
    private ChunkDelta<BlockState, NbtCompound> clientDelta;
    /** Stores world. */
    private ClientWorld world;

    /**
     * World X of local coordinate origin (0,y,0). Pre-calculated as {@code chunkX << 4}.
     * Cached to avoid repeated bit-shift in the block visit hot path.
     */
    private int baseX;

    /**
     * World Z of local coordinate origin (0,y,0). Pre-calculated as {@code chunkZ << 4}.
     * Cached to avoid repeated bit-shift in the block visit hot path.
     */
    private int baseZ;

    /**
     * Prepares this visitor for a new delta application.
     * Must be called before passing this visitor to {@link ChunkDelta#accept}.
     *
     * @param clientDelta client-side delta tracker to keep in sync with the server
     * @param world       the client world to mutate
     * @param chunkX      chunk X coordinate
     * @param chunkZ      chunk Z coordinate
     */
    void reset(
            final ChunkDelta<BlockState, NbtCompound> clientDelta,
            final ClientWorld world,
            final int chunkX,
            final int chunkZ) {
        this.clientDelta = clientDelta;
        this.world = world;
        this.baseX = chunkX << 4;
        this.baseZ = chunkZ << 4;
    }

    /**
     * Applies a single block-state change to the client world.
     *
     * <p>
     * Flag {@code 3} is {@code UPDATE_CLIENTS | UPDATE_NEIGHBORS}, triggering
     * visual and neighbor updates without sending another packet to the server.
     *
     * @param x     local chunk X coordinate
     * @param y     world Y coordinate
     * @param z     local chunk Z coordinate
     * @param state the new block state to apply
     */
    @Override
    public void visitBlock(final int x, final int y, final int z, final BlockState state) {
        mutablePos.set(baseX + x, y, baseZ + z);
        // Flag 3 = UPDATE_CLIENTS | UPDATE_NEIGHBORS
        world.setBlockState(mutablePos, state, 3);
        clientDelta.addBlockChange(x, y, z, state, false);
        world.scheduleBlockRenders(baseX + x, y, baseZ + z);
    }

    /**
     * Applies a block entity NBT update to the client world.
     *
     * <p>
     * Skips silently if the current block state at the target position does
     * not support a block entity, or if NBT deserialization returns null.
     *
     * @param x   local chunk X coordinate
     * @param y   world Y coordinate
     * @param z   local chunk Z coordinate
     * @param nbt the block entity data to apply
     */
    @Override
    public void visitBlockEntity(final int x, final int y, final int z, final NbtCompound nbt) {
        clientDelta.addBlockEntityData(x, y, z, nbt, false);

        // Trial spawners contain server-side registry-backed config that the client
        // cannot decode from the full persistence payload. Vanilla already sends
        // their client-safe initial data, so keep the payload for delta tracking but
        // do not replay it through BlockEntity.createFromNbt.
        if (isVanillaOwnedBlockEntity(nbt)) {
            return;
        }

        final BlockPos pos = new BlockPos(baseX + x, y, baseZ + z);
        if (!canHaveBlockEntity(pos)) {
            return;
        }

        final BlockEntity be = deserializeBlockEntity(pos, nbt);
        if (be == null) {
            return;
        }

        replaceBlockEntity(pos, be);
    }

    /**
     * Returns whether the block entity's client state is authoritative from vanilla.
     *
     * @param nbt block entity payload
     * @return {@code true} for block entities that must not receive full persistence NBT on the client
     */
    static boolean isVanillaOwnedBlockEntity(final NbtCompound nbt) {
        return nbt.getString("id")
                .map("minecraft:trial_spawner"::equals)
                .orElse(false);
    }

    /**
     * Ignores entity NBT because vanilla entity tracking already synchronizes
     * server entities to the client.
     *
     * @param nbt entity data supplied by the custom delta, intentionally ignored
     */
    @Override
    public void visitEntity(final NbtCompound nbt) {
        // Vanilla entity tracking already synchronizes these entities to the client.
    }

    /**
     * Returns true if the block at the given position currently supports a block entity.
     *
     * @param pos the world position to check
     * @return true if the block state at pos has a block entity
     */
    private boolean canHaveBlockEntity(final BlockPos pos) {
        return world.getBlockState(pos)
                .hasBlockEntity();
    }

    /**
     * Deserializes a block entity from NBT at the given position.
     * Logs a warning and returns null if deserialization fails.
     *
     * @param pos the world position of the block entity
     * @param nbt the NBT data to deserialize
     * @return the deserialized BlockEntity, or null if creation failed
     */
    private BlockEntity deserializeBlockEntity(final BlockPos pos, final NbtCompound nbt) {
        final BlockState currentState = world.getBlockState(pos);
        final BlockEntity be = BlockEntity.createFromNbt(pos, currentState, nbt, world.getRegistryManager());

        if (be == null) {
            Chunkis.LOGGER.warn("Chunkis: Could not create block entity from NBT at {}", pos);
        }

        return be;
    }

    /**
     * Removes the existing block entity at the given position and replaces it
     * with the provided instance.
     *
     * @param pos the world position to update
     * @param be  the new block entity to install
     */
    private void replaceBlockEntity(final BlockPos pos, final BlockEntity be) {
        world.removeBlockEntity(pos);
        world.addBlockEntity(be);
    }

}
