package io.liparakis.chunkis.world.restoration.capture;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.storage.model.CisConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
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
 *   <li>block entities whose current block state no longer supports block entities
 *       (sweep path only — stale delta data is also removed)</li>
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
     * NBT key required by Minecraft's block entity deserializer to identify
     * the block entity type on load.
     */
    private static final String BLOCK_ENTITY_ID_KEY = "id";

    private ChunkBlockEntityCapture() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures one block entity into a delta.
     *
     * <p>Intended for proactive hooks where the caller already knows the block
     * entity is relevant. Save-time chunk sweeps use
     * {@link #captureBlockEntities(WorldChunk, RegistryWrapper.WrapperLookup, ChunkDelta)}
     * so stale entries can be validated against the chunk's current block state.</p>
     *
     * @param blockEntity     block entity to capture; must not be {@code null}
     * @param registryManager registry wrapper used for NBT serialization; must not be {@code null}
     * @param delta           destination delta; must not be {@code null}
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
        // World may be null if the BE has been detached; getWorld() instanceof handles that.
        final ServerWorld serverWorld = asServerWorld(blockEntity.getWorld());
        final ChunkPos chunkPos = serverWorld != null ? new ChunkPos(pos) : null;

        if (blockEntity.isRemoved()) {
            traceSkipped(serverWorld, chunkPos, pos, "capture skipped: block entity removed");
            return;
        }

        final NbtCompound nbt = trySerializeBlockEntity(blockEntity, registryManager);
        if (isEmptyNbt(nbt)) {
            traceSkipped(serverWorld, chunkPos, pos, "capture skipped: block entity serialized empty NBT");
            return;
        }

        storeCapturedBlockEntity(serverWorld, chunkPos, pos, blockEntity, nbt, delta);
    }

    /**
     * Captures every live block entity currently attached to a chunk.
     *
     * <p>This is the save-path safety sweep. It catches block entities that changed
     * without going through a proactive dirty callback. It also removes stale delta
     * entries when the block at a block entity's position no longer supports block
     * entities.</p>
     *
     * @param chunk           chunk being saved; must not be {@code null}
     * @param registryManager registry wrapper used for NBT serialization; must not be {@code null}
     * @param delta           destination delta; must not be {@code null}
     */
    public static void captureBlockEntities(
            final WorldChunk chunk,
            final RegistryWrapper.WrapperLookup registryManager,
            final ChunkDelta<?, NbtCompound> delta
    ) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(registryManager, "registryManager");
        Objects.requireNonNull(delta, "delta");

        final ServerWorld serverWorld = asServerWorld(chunk.getWorld());
        final ChunkPos chunkPos = chunk.getPos();

        for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            captureBlockEntityFromChunkSweep(serverWorld, chunkPos, chunk, blockEntity, registryManager, delta);
        }
    }

    /**
     * Captures one block entity during a chunk-wide save sweep.
     *
     * <p>The chunk's current block state is validated before serialization. If the
     * block no longer supports a block entity, any stale delta payload for that
     * position is removed to avoid carrying forward orphaned data.</p>
     *
     * <p>{@code serverWorld} and {@code chunkPos} are pre-resolved by the caller to
     * avoid repeated casts and allocations across the sweep loop.</p>
     *
     * @param serverWorld     server world, or {@code null} if the chunk's world is not a server world
     * @param chunkPos        position of the chunk being swept
     * @param chunk           source chunk
     * @param blockEntity     block entity candidate; may be {@code null} (defensive)
     * @param registryManager registry wrapper used for NBT serialization
     * @param delta           destination delta
     */
    private static void captureBlockEntityFromChunkSweep(
            @Nullable final ServerWorld serverWorld,
            final ChunkPos chunkPos,
            final WorldChunk chunk,
            @Nullable final BlockEntity blockEntity,
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
            traceSkipped(serverWorld, chunkPos, pos, "capture skipped: current block state has no block entity");
            return;
        }

        final NbtCompound nbt = trySerializeBlockEntity(blockEntity, registryManager);
        if (isEmptyNbt(nbt)) {
            traceSkipped(serverWorld, chunkPos, pos, "capture skipped: block entity serialized empty NBT");
            return;
        }

        storeCapturedBlockEntity(serverWorld, chunkPos, pos, blockEntity, nbt, delta);
    }

    /**
     * Serializes a block entity and ensures the required {@value #BLOCK_ENTITY_ID_KEY}
     * field is present.
     *
     * <p>Returns {@code null} if serialization fails or the block entity type has no
     * registry ID. A broken block entity must not abort saving the rest of the chunk.</p>
     *
     * @param blockEntity     block entity to serialize
     * @param registryManager registry wrapper for serialization
     * @return serialized NBT with an {@value #BLOCK_ENTITY_ID_KEY} field, or {@code null}
     */
    @Nullable
    private static NbtCompound trySerializeBlockEntity(
            final BlockEntity blockEntity,
            final RegistryWrapper.WrapperLookup registryManager
    ) {
        try {
            final NbtCompound nbt = blockEntity.createNbtWithIdentifyingData(registryManager);
            if (nbt == null || nbt.isEmpty()) {
                return null;
            }
            return nbt.contains(BLOCK_ENTITY_ID_KEY) ? nbt : injectBlockEntityId(blockEntity, nbt);
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
     * <p>Block entity NBT without an ID key is unloadable by Minecraft's deserializer,
     * so unregistered types are skipped rather than written with corrupt data.</p>
     *
     * @param blockEntity source block entity
     * @param nbt         NBT compound to update in-place
     * @return updated {@code nbt}, or {@code null} if the type is unregistered
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
     * Stores serialized block entity NBT in the delta at the block entity's local
     * chunk coordinates.
     *
     * <p>World X/Z are converted to local chunk coordinates with {@code & 15}
     * ({@link CisConstants#COORD_MASK}), which is equivalent to floor-modulo 16
     * and is correct for negative world coordinates.</p>
     *
     * @param worldPos absolute block position
     * @param nbt      serialized block entity NBT (non-null, non-empty)
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
     * Stores block entity data in the delta and emits the corresponding capture trace.
     *
     * @param serverWorld resolved server world, or {@code null} when tracing is unavailable
     * @param chunkPos    chunk position containing the block entity
     * @param worldPos    absolute block position
     * @param blockEntity captured block entity
     * @param nbt         serialized block entity payload
     * @param delta       destination delta
     */
    private static void storeCapturedBlockEntity(
            @Nullable final ServerWorld serverWorld,
            @Nullable final ChunkPos chunkPos,
            final BlockPos worldPos,
            final BlockEntity blockEntity,
            final NbtCompound nbt,
            final ChunkDelta<?, NbtCompound> delta
    ) {
        storeInDelta(worldPos, nbt, delta);
        if (serverWorld != null && chunkPos != null) {
            PayloadWatchTracer.traceCapturedBlockEntity(
                    serverWorld.getRegistryKey().getValue().toString(),
                    chunkPos,
                    worldPos,
                    blockEntity,
                    nbt
            );
        }
    }

    /**
     * Removes stale block entity data from the delta for a position where the block
     * state no longer supports a block entity.
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
     * Emits a skip trace event if {@code serverWorld} is non-null. No-op otherwise.
     *
     * <p>Consolidates the repeated {@code if (world instanceof ServerWorld)} guard
     * that precedes every {@link PayloadWatchTracer#traceSkippedBlockEntityCapture}
     * call.</p>
     *
     * @param serverWorld resolved server world, or {@code null}
     * @param chunkPos    chunk position for the trace
     * @param pos         absolute block position for the trace
     * @param reason      human-readable skip reason
     */
    private static void traceSkipped(
            @Nullable final ServerWorld serverWorld,
            @Nullable final ChunkPos chunkPos,
            final BlockPos pos,
            final String reason
    ) {
        if (serverWorld != null && chunkPos != null) {
            PayloadWatchTracer.traceSkippedBlockEntityCapture(serverWorld, chunkPos, pos, reason);
        }
    }

    /**
     * Casts {@code world} to {@link ServerWorld} if possible, otherwise returns {@code null}.
     *
     * <p>Used to avoid repeated {@code instanceof} checks in callers that need the
     * server world for both tracing and registry key access.</p>
     *
     * @param world world to check; may be {@code null}
     * @return the world as a {@link ServerWorld}, or {@code null}
     */
    @Nullable
    private static ServerWorld asServerWorld(@Nullable final World world) {
        return world instanceof ServerWorld sw ? sw : null;
    }

    /**
     * Returns {@code true} if {@code nbt} is {@code null} or empty, indicating
     * there is no usable data to store.
     *
     * @param nbt compound to inspect; may be {@code null}
     * @return {@code true} if null or empty
     */
    private static boolean isEmptyNbt(@Nullable final NbtCompound nbt) {
        return nbt == null || nbt.isEmpty();
    }
}
