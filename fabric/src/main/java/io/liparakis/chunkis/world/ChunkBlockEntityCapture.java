package io.liparakis.chunkis.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.storage.model.CisConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * Captures block entities from chunks into Chunkis delta storage.
 *
 * <p>This utility serializes live block entities into their local chunk position
 * so they can be restored alongside sparse block changes. It is used both by
 * proactive block-entity hooks and by save-time consistency sweeps.</p>
 *
 * <p>Skipped cases:</p>
 * <ul>
 *   <li>removed block entities</li>
 *   <li>block entities whose current block state no longer supports block entities</li>
 *   <li>block entities that serialize to null or empty NBT</li>
 *   <li>block entities whose type has no registry ID</li>
 * </ul>
 *
 * <p><b>Threading:</b> must run on the server thread. Block entity serialization
 * and chunk state access are not generally thread-safe.</p>
 *
 * @author Liparakis
 * @version 2.2
 */
public final class ChunkBlockEntityCapture {

    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * NBT key required by Minecraft's block entity deserializer.
     */
    private static final String BLOCK_ENTITY_ID_KEY = "id";

    private ChunkBlockEntityCapture() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures one block entity into a delta.
     *
     * <p>This method is intended for proactive hooks where the caller already knows
     * the block entity is relevant. Save-time chunk sweeps use
     * {@link #captureBlockEntities(WorldChunk, RegistryWrapper.WrapperLookup, ChunkDelta)}
     * so stale block-entity entries can be checked against the chunk's current block
     * state.</p>
     *
     * @param blockEntity     block entity to capture
     * @param registryManager registry wrapper used for NBT serialization
     * @param delta           destination delta
     */
    public static void captureBlockEntity(
            final BlockEntity blockEntity,
            final RegistryWrapper.WrapperLookup registryManager,
            final ChunkDelta<?, NbtCompound> delta
    ) {
        Objects.requireNonNull(blockEntity, "blockEntity");
        Objects.requireNonNull(registryManager, "registryManager");
        Objects.requireNonNull(delta, "delta");

        final BlockPos pos = blockEntity.getPos();
        if (blockEntity.isRemoved()) {
            if (blockEntity.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                PayloadWatchTracer.traceSkippedBlockEntityCapture(
                        serverWorld,
                        new net.minecraft.util.math.ChunkPos(pos),
                        pos,
                        "capture skipped: block entity removed"
                );
            }
            return;
        }
        final NbtCompound nbt = trySerializeBlockEntity(blockEntity, registryManager);

        if (isEmptyNbt(nbt)) {
            if (blockEntity.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                PayloadWatchTracer.traceSkippedBlockEntityCapture(
                        serverWorld,
                        new net.minecraft.util.math.ChunkPos(pos),
                        pos,
                        "capture skipped: block entity serialized empty NBT"
                );
            }
            return;
        }

        storeInDelta(pos, nbt, delta);
        if (blockEntity.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            PayloadWatchTracer.traceCapturedBlockEntity(
                    serverWorld.getRegistryKey().getValue().toString(),
                    new net.minecraft.util.math.ChunkPos(pos),
                    pos,
                    blockEntity,
                    nbt
            );
        }
    }

    /**
     * Captures every live block entity currently attached to a chunk.
     *
     * <p>This is the save-path safety sweep. It catches block entities that changed
     * without going through a proactive dirty callback. It also avoids preserving
     * stale live block entities when the block at their position no longer supports
     * block entities.</p>
     *
     * @param chunk           chunk being saved
     * @param registryManager registry wrapper used for NBT serialization
     * @param delta           destination delta
     */
    public static void captureBlockEntities(
            final WorldChunk chunk,
            final RegistryWrapper.WrapperLookup registryManager,
            final ChunkDelta<?, NbtCompound> delta
    ) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(registryManager, "registryManager");
        Objects.requireNonNull(delta, "delta");

        for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            captureBlockEntityFromChunkSweep(chunk, blockEntity, registryManager, delta);
        }
    }

    /**
     * Captures one block entity during a chunk-wide save sweep.
     *
     * <p>The chunk state is checked before serialization. If the block no longer
     * supports a block entity, any stale delta payload for that position is removed.</p>
     *
     * @param chunk           source chunk
     * @param blockEntity     block entity candidate
     * @param registryManager registry wrapper used for NBT serialization
     * @param delta           destination delta
     */
    private static void captureBlockEntityFromChunkSweep(
            final WorldChunk chunk,
            final BlockEntity blockEntity,
            final RegistryWrapper.WrapperLookup registryManager,
            final ChunkDelta<?, NbtCompound> delta
    ) {
        if (blockEntity == null || blockEntity.isRemoved()) {
            return;
        }

        final BlockPos pos = blockEntity.getPos();
        final BlockState state = chunk.getBlockState(pos);

        if (!state.hasBlockEntity()) {
            removeFromDelta(pos, delta);
            if (chunk.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                PayloadWatchTracer.traceSkippedBlockEntityCapture(
                        serverWorld,
                        chunk.getPos(),
                        pos,
                        "capture skipped: current block state has no block entity"
                );
            }
            return;
        }

        final NbtCompound nbt = trySerializeBlockEntity(blockEntity, registryManager);

        if (isEmptyNbt(nbt)) {
            if (chunk.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                PayloadWatchTracer.traceSkippedBlockEntityCapture(
                        serverWorld,
                        chunk.getPos(),
                        pos,
                        "capture skipped: block entity serialized empty NBT"
                );
            }
            return;
        }

        storeInDelta(pos, nbt, delta);
        PayloadWatchTracer.traceCapturedBlockEntity(
                chunk.getWorld().getRegistryKey().getValue().toString(),
                chunk.getPos(),
                pos,
                blockEntity,
                nbt
        );
    }

    /**
     * Serializes a block entity and ensures the required {@value #BLOCK_ENTITY_ID_KEY}
     * field is present.
     *
     * <p>Returns {@code null} if the block entity cannot be serialized or its type
     * has no registry ID. A broken block entity should not abort saving the rest of
     * the chunk.</p>
     *
     * @param blockEntity     block entity to serialize
     * @param registryManager registry wrapper for serialization
     * @return serialized NBT, or {@code null}
     */
    @Nullable
    private static NbtCompound trySerializeBlockEntity(
            final BlockEntity blockEntity,
            final RegistryWrapper.WrapperLookup registryManager
    ) {
        try {
            final NbtCompound nbt =
                    blockEntity.createNbtWithIdentifyingData(registryManager);

            if (nbt == null || nbt.isEmpty()) {
                return null;
            }

            return nbt.contains(BLOCK_ENTITY_ID_KEY)
                    ? nbt
                    : injectBlockEntityId(blockEntity, nbt);
        } catch (final Exception e) {
            LOGGER.warn(
                    "Chunkis: Failed to serialize block entity at {} of type {}",
                    blockEntity.getPos(),
                    blockEntity.getType(),
                    e
            );
            return null;
        }
    }

    /**
     * Injects the registry ID string into serialized block entity NBT.
     *
     * <p>Saving block entity NBT without an ID would produce unloadable data, so
     * unregistered block entity types are skipped.</p>
     *
     * @param blockEntity source block entity
     * @param nbt         NBT compound to update
     * @return updated NBT, or {@code null} if the type is unregistered
     */
    @Nullable
    private static NbtCompound injectBlockEntityId(
            final BlockEntity blockEntity,
            final NbtCompound nbt
    ) {
        final var typeId = BlockEntityType.getId(blockEntity.getType());

        if (typeId == null) {
            LOGGER.warn(
                    "Chunkis: Block entity at {} has unregistered type: {}",
                    blockEntity.getPos(),
                    blockEntity.getType()
            );
            return null;
        }

        nbt.putString(BLOCK_ENTITY_ID_KEY, typeId.toString());
        return nbt;
    }

    /**
     * Stores serialized block entity NBT in the delta.
     *
     * <p>World X/Z are converted to local chunk coordinates with {@code & 15}.
     * This is equivalent to floor-modulo 16 for Minecraft block coordinates,
     * including negative world positions.</p>
     *
     * @param worldPos absolute block position
     * @param nbt      serialized block entity NBT
     * @param delta    destination delta
     */
    private static void storeInDelta(
            final BlockPos worldPos,
            final NbtCompound nbt,
            final ChunkDelta<?, NbtCompound> delta
    ) {
        delta.addBlockEntityData(
                worldPos.getX() & CisConstants.COORD_MASK,
                worldPos.getY(),
                worldPos.getZ() & CisConstants.COORD_MASK,
                nbt
        );
    }

    /**
     * Removes stale block entity data from the delta.
     *
     * @param worldPos absolute block position
     * @param delta    destination delta
     */
    private static void removeFromDelta(
            final BlockPos worldPos,
            final ChunkDelta<?, NbtCompound> delta
    ) {
        delta.removeBlockEntityData(
                worldPos.getX() & CisConstants.COORD_MASK,
                worldPos.getY(),
                worldPos.getZ() & CisConstants.COORD_MASK
        );
    }

    /**
     * Returns whether an NBT compound has no usable data.
     *
     * @param nbt compound to inspect, may be {@code null}
     * @return {@code true} if null or empty
     */
    private static boolean isEmptyNbt(@Nullable final NbtCompound nbt) {
        return nbt == null || nbt.isEmpty();
    }
}
