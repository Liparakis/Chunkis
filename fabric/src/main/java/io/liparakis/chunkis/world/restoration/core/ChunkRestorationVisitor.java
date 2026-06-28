package io.liparakis.chunkis.world.restoration.core;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityQueries;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Applies persisted chunk delta entries into a live chunk during restore.
 *
 * <p>This class owns the low-level restore walk so {@link ChunkRestorer} can stay
 * focused on the higher-level restore transaction and tracing lifecycle.</p>
 */
final class ChunkRestorationVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {

    private static final Logger LOGGER = Chunkis.LOGGER;
    private static final String BLOCK_ENTITY_ID_KEY = "id";
    private static final String BLOCK_TRACE_SOURCE = "ChunkRestorer.RestorationVisitor#visitBlock";
    private static final String ENTITY_TRACE_SOURCE = "ChunkRestorer.RestorationVisitor#visitEntity";

    private final ServerWorld world;
    private final WorldChunk chunk;
    private final ChunkPos chunkPosition;
    private final ChunkDelta<BlockState, NbtCompound> runtimeDelta;
    private final boolean replayLegacyEntities;
    private final String operationId;
    private final ChunkRestorer.BlockApplyFailureCounters blockApplyFailureCounters;
    private int appliedBlocksCount;
    private int restoredBlockEntitiesCount;
    private int restoredEntitiesCount;

    ChunkRestorationVisitor(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> sourceDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            @Nullable final String operationId
    ) {
        this.world = world;
        this.chunk = chunk;
        this.chunkPosition = chunk.getPos();
        this.runtimeDelta = runtimeDelta;
        this.replayLegacyEntities = shouldReplayLegacyEntities(sourceDelta);
        this.operationId = operationId;
        this.blockApplyFailureCounters = new ChunkRestorer.BlockApplyFailureCounters();
        if (this.replayLegacyEntities && this.runtimeDelta != null) {
            this.runtimeDelta.setEntities(sourceDelta.getEntitiesList(), false);
        }
    }

    /**
     * Removes regenerated non-player entities before replaying legacy persisted entities.
     *
     * <p>This prevents vanilla one-time population from duplicating entities that
     * are still owned by the persisted delta payload.</p>
     *
     * @param sourceDelta loaded source delta
     */
    void cleanupReplayedEntities(final ChunkDelta<BlockState, NbtCompound> sourceDelta) {
        if (!shouldCleanupReplayedEntities(sourceDelta)) {
            return;
        }

        final Set<UUID> allowedUuids = collectPersistedEntityUuids(sourceDelta);
        final List<Entity> liveEntities = world.getOtherEntities(null, chunkEntitySearchBox());
        for (final Entity entity : liveEntities) {
            if (entity instanceof PlayerEntity) {
                continue;
            }
            if (allowedUuids.contains(entity.getUuid())) {
                continue;
            }
            entity.discard();
        }
    }

    /**
     * Performs final runtime-delta cleanup after restoration.
     */
    void finishRestoration() {
        if (runtimeDelta != null) {
            runtimeDelta.markSaved();
        }
    }

    int appliedBlocksCount() {
        return appliedBlocksCount;
    }

    int restoredBlockEntitiesCount() {
        return restoredBlockEntitiesCount;
    }

    int restoredEntitiesCount() {
        return restoredEntitiesCount;
    }

    ChunkRestorer.BlockApplyFailureCounters blockApplyFailureCounters() {
        return blockApplyFailureCounters;
    }

    @Override
    public void visitBlock(
            final int localX,
            final int localY,
            final int localZ,
            final BlockState state
    ) {
        blockApplyFailureCounters.recordVisitedInstruction();
        if (state == null) {
            blockApplyFailureCounters.recordNullState();
            return;
        }

        final BlockPos worldPos = chunkPosition.getBlockPos(localX, localY, localZ);
        final BlockState previousState = chunk.getBlockState(worldPos);
        PayloadWatchTracer.traceRestoreInstructionVisited(
                chunk,
                worldPos,
                previousState,
                state,
                operationId,
                BLOCK_TRACE_SOURCE
        );

        if (!ChunkRestorer.applyBlockChange(
                chunk,
                chunkPosition,
                localX,
                localY,
                localZ,
                state,
                worldPos,
                blockApplyFailureCounters,
                operationId
        )) {
            PayloadWatchTracer.traceRestoreBlockFailure(
                    world,
                    chunkPosition,
                    worldPos,
                    operationId,
                    "restore failed before block reached live world"
            );
            return;
        }

        copyBlockToRuntimeDelta(localX, localY, localZ, state);
        blockApplyFailureCounters.recordAppliedBlock();
        appliedBlocksCount++;
        PayloadWatchTracer.traceRestoredBlock(chunk, worldPos, state, operationId);
    }

    @Override
    public void visitBlockEntity(
            final int localX,
            final int localY,
            final int localZ,
            final NbtCompound nbt
    ) {
        if (nbt != null) {
            restoreBlockEntity(localX, localY, localZ, nbt);
        }
    }

    @Override
    public void visitEntity(final NbtCompound nbt) {
        if (nbt != null) {
            PayloadWatchTracer.traceRestoreEntityInstructionVisited(
                    world,
                    chunkPosition,
                    nbt,
                    operationId,
                    ENTITY_TRACE_SOURCE
            );
        }
        if (replayLegacyEntities && nbt != null) {
            EntityPayloadNbt.findUuid(nbt)
                    .ifPresent(uuid -> ScheduledEntityReplayQueue.schedule(
                            world,
                            chunkPosition,
                            uuid.toString(),
                            nbt
                    ));
        } else if (nbt != null) {
            final String entityUuid = EntityPayloadNbt.findUuidString(nbt).orElse("<missing-uuid>");
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world,
                    chunkPosition,
                    entityUuid,
                    operationId,
                    "restore skipped: replayLegacyEntities=false"
            );
        }
    }

    private boolean shouldCleanupReplayedEntities(
            final ChunkDelta<BlockState, NbtCompound> sourceDelta
    ) {
        return replayLegacyEntities
                && sourceDelta != null
                && sourceDelta.shouldSuppressInitialRepopulation();
    }

    private Box chunkEntitySearchBox() {
        return ChunkEntityQueries.chunkColumnBox(world, chunkPosition);
    }

    private void copyBlockToRuntimeDelta(
            final int localX,
            final int localY,
            final int localZ,
            final BlockState state
    ) {
        if (runtimeDelta != null) {
            runtimeDelta.addBlockChange(localX, localY, localZ, state, false);
        }
    }

    /**
     * Restores a block entity from NBT when the current block state supports it.
     */
    private void restoreBlockEntity(
            final int localX,
            final int localY,
            final int localZ,
            final NbtCompound nbt
    ) {
        final BlockPos worldPos = chunkPosition.getBlockPos(localX, localY, localZ);
        final BlockState currentState = chunk.getBlockState(worldPos);

        if (!currentState.hasBlockEntity()) {
            PayloadWatchTracer.traceRestoreBlockEntitySkipped(
                    world,
                    chunkPosition,
                    worldPos,
                    operationId,
                    "restore skipped: missing block state"
            );
            return;
        }

        if (!isBlockEntityNbtCompatibleWithState(nbt, currentState)) {
            PayloadWatchTracer.traceRestoreBlockEntitySkipped(
                    world,
                    chunkPosition,
                    worldPos,
                    operationId,
                    "restore skipped: block entity type incompatible with current block state"
            );
            return;
        }

        final BlockEntity blockEntity = BlockEntity.createFromNbt(
                worldPos,
                currentState,
                nbt,
                world.getRegistryManager()
        );
        if (blockEntity == null) {
            LOGGER.warn("Failed to create block entity from NBT at {}", worldPos);
            PayloadWatchTracer.traceRestoreBlockEntitySkipped(
                    world,
                    chunkPosition,
                    worldPos,
                    operationId,
                    "restore skipped: block entity could not be created from NBT"
            );
            return;
        }

        chunk.removeBlockEntity(worldPos);
        chunk.addBlockEntity(blockEntity);
        if (runtimeDelta != null) {
            runtimeDelta.addBlockEntityData(localX, localY, localZ, nbt, false);
        }
        restoredBlockEntitiesCount++;
        PayloadWatchTracer.traceRestoredBlockEntity(world, chunkPosition, worldPos, blockEntity, nbt, operationId);
    }

    private static boolean isBlockEntityNbtCompatibleWithState(
            final NbtCompound nbt,
            final BlockState currentState
    ) {
        final Optional<String> rawId = nbt.getString(BLOCK_ENTITY_ID_KEY);
        if (rawId.isEmpty()) {
            return false;
        }

        final Identifier id = Identifier.tryParse(rawId.get());
        if (id == null) {
            return false;
        }

        final BlockEntityType<?> type = Registries.BLOCK_ENTITY_TYPE.get(id);
        return type != null && type.supports(currentState);
    }

    private static boolean shouldReplayLegacyEntities(
            final ChunkDelta<BlockState, NbtCompound> sourceDelta
    ) {
        return sourceDelta != null && sourceDelta.countNonNullEntities() > 0;
    }

    private static Set<UUID> collectPersistedEntityUuids(
            final ChunkDelta<BlockState, NbtCompound> sourceDelta
    ) {
        final Set<UUID> uuids = new HashSet<>();
        sourceDelta.forEachEntity(nbt -> {
            if (nbt != null) {
                EntityPayloadNbt.findUuid(nbt).ifPresent(uuids::add);
            }
        });
        return uuids;
    }
}

