package io.liparakis.chunkis.world.restoration.core;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityQueries;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Applies persisted chunk delta entries into a live chunk during restore.
 *
 * <p>This class owns the low-level restore walk so {@link ChunkRestorer} can stay
 * focused on the higher-level restore transaction and tracing lifecycle.</p>
 */
final class ChunkRestorationVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound>,
        ChunkDelta.BlockInstructionVisitor<BlockState> {

    /**
     * Logger instance reference.
     */
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Key identifying block entity types in NBT compounds.
     */
    private static final String BLOCK_ENTITY_ID_KEY = "id";

    /**
     * Log source tag identifier mapping for block restore operations.
     */
    private static final String BLOCK_TRACE_SOURCE = "ChunkRestorer.RestorationVisitor#visitBlock";

    /**
     * Log source tag identifier mapping for entity restore operations.
     */
    private static final String ENTITY_TRACE_SOURCE = "ChunkRestorer.RestorationVisitor#visitEntity";

    /**
     * Target ServerWorld instance context.
     */
    private final ServerWorld world;

    /**
     * Target WorldChunk instance context.
     */
    private final WorldChunk chunk;

    /**
     * Position bounds matching the target chunk.
     */
    private final ChunkPos chunkPosition;
    /**
     * Cached chunk start X used to avoid recreating absolute positions from {@link ChunkPos}.
     */
    private final int chunkStartX;
    /**
     * Cached chunk start Z used to avoid recreating absolute positions from {@link ChunkPos}.
     */
    private final int chunkStartZ;
    /**
     * Cached vertical bounds for fast restore-time range checks.
     */
    private final int bottomY;
    /**
     * Cached inclusive top Y for fast restore-time range checks.
     */
    private final int topYInclusive;
    /**
     * Cached section array so per-block replay does not repeatedly route through {@link WorldChunk}.
     */
    private final ChunkSection[] sections;
    /**
     * Reused mutable world position for block replay to avoid one {@link BlockPos} allocation per block.
     */
    private final BlockPos.Mutable mutableWorldPos = new BlockPos.Mutable();
    /**
     * Snapshot of whether payload-watch tracing is active for this restore pass.
     */
    private final boolean tracePayloadWatches;
    /**
     * Whether restore cleared the target chunk to air before replay.
     */
    private final boolean clearedToAir;

    /**
     * Runtime block delta state being constructed/populated.
     */
    private final ChunkDelta<BlockState, NbtCompound> runtimeDelta;

    /**
     * True if legacy entities should be replayed.
     */
    private final boolean replayLegacyEntities;

    /**
     * Unique execution/operation trace ID label.
     */
    private final String operationId;

    /**
     * Tracker instance maintaining failure occurrences during block application loops.
     */
    private final ChunkRestorer.BlockApplyFailureCounters blockApplyFailureCounters;

    /**
     * Tracks which chunk sections were mutated so counts can be recomputed once per section.
     */
    private final boolean[] touchedSections;

    /**
     * Cumulative count of successfully restored block coordinates.
     */
    private int appliedBlocksCount;

    /**
     * Cumulative count of successfully restored block entity states.
     */
    private int restoredBlockEntitiesCount;

    /**
     * Constructor.
     *
     * @param world        target server world
     * @param chunk        target world chunk
     * @param sourceDelta  loaded source delta containing block restoration data
     * @param runtimeDelta target runtime delta being updated in-place
     * @param operationId  optional trace execution identifier, may be null
     */
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
        this.chunkStartX = this.chunkPosition.getStartX();
        this.chunkStartZ = this.chunkPosition.getStartZ();
        this.bottomY = chunk.getBottomY();
        this.topYInclusive = chunk.getTopYInclusive();
        this.sections = chunk.getSectionArray();
        this.tracePayloadWatches = ChunkTraceWatchpoints.hasPayloadWatches();
        this.clearedToAir = !CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(sourceDelta.getChunkMetadata());
        this.runtimeDelta = runtimeDelta;
        this.replayLegacyEntities = shouldReplayLegacyEntities(sourceDelta);
        this.operationId = operationId;
        this.blockApplyFailureCounters = new ChunkRestorer.BlockApplyFailureCounters();
        this.touchedSections = new boolean[this.sections.length];
        if (this.replayLegacyEntities && this.runtimeDelta != null) {
            this.runtimeDelta.setEntities(sourceDelta.getEntitiesList(), false);
        }
    }

    /**
     * Checks if a serialized block entity's type ID matches and supports current block state context.
     *
     * @param nbt          block entity NBT compound
     * @param currentState block state of coordinates to evaluate
     * @return true if compatible
     */
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

    /**
     * Checks if source delta requires spawning/restoring legacy entities.
     *
     * @param sourceDelta source delta to query
     * @return true if replay is required
     */
    private static boolean shouldReplayLegacyEntities(
            final ChunkDelta<BlockState, NbtCompound> sourceDelta
    ) {
        return sourceDelta != null && sourceDelta.countNonNullEntities() > 0;
    }

    /**
     * Resolves the list of UUID records from entities defined in target delta.
     *
     * @param sourceDelta source delta containing entity payloads
     * @return set of UUIDs
     */
    private static Set<UUID> collectPersistedEntityUuids(
            final ChunkDelta<BlockState, NbtCompound> sourceDelta
    ) {
        final Set<UUID> uuids = new HashSet<>();
        sourceDelta.forEachEntity(nbt -> {
            if (nbt != null) {
                EntityPayloadNbt.findUuid(nbt)
                        .ifPresent(uuids::add);
            }
        });
        return uuids;
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
        recalculateTouchedSectionCounts();
        if (runtimeDelta != null) {
            runtimeDelta.markSaved();
        }
    }

    /**
     * Rebuilds section counts after restore-time raw container writes.
     */
    private void recalculateTouchedSectionCounts() {
        for (int sectionIndex = 0; sectionIndex < touchedSections.length; sectionIndex++) {
            if (!touchedSections[sectionIndex]) {
                continue;
            }
            final ChunkSection section = sections[sectionIndex];
            if (section != null) {
                section.calculateCounts();
            }
        }
    }

    /**
     * Returns cumulative applied blocks.
     *
     * @return applied blocks count
     */
    int appliedBlocksCount() {
        return appliedBlocksCount;
    }

    /**
     * Returns cumulative restored block entities.
     *
     * @return restored block entities count
     */
    int restoredBlockEntitiesCount() {
        return restoredBlockEntitiesCount;
    }

    /**
     * Returns whether this restore still owns legacy entity replay work.
     *
     * @return {@code true} if persisted entities should be visited/replayed
     */
    boolean replayLegacyEntities() {
        return replayLegacyEntities;
    }

    /**
     * Returns failure tracking accumulator statistics.
     *
     * @return reference to BlockApplyFailureCounters tracker
     */
    ChunkRestorer.BlockApplyFailureCounters blockApplyFailureCounters() {
        return blockApplyFailureCounters;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitBlock(
            final int localX,
            final int localY,
            final int localZ,
            final BlockState state
    ) {
        visitBlockInternal(localX, localY, localZ, -1, state);
    }

    /**
     * Restores one block while preserving the already-decoded palette id for runtime-delta copy.
     *
     * @param localX local chunk X coordinate
     * @param localY absolute world Y coordinate
     * @param localZ local chunk Z coordinate
     * @param paletteId decoded palette id from the source delta
     * @param state restored block state
     */
    @Override
    public void visitBlock(
            final int localX,
            final int localY,
            final int localZ,
            final int paletteId,
            final BlockState state
    ) {
        visitBlockInternal(localX, localY, localZ, paletteId, state);
    }

    /**
     * Shared implementation for restore-time block replay.
     *
     * @param localX local chunk X coordinate
     * @param localY absolute world Y coordinate
     * @param localZ local chunk Z coordinate
     * @param paletteId decoded palette id, or {@code -1} when unavailable
     * @param state restored block state
     */
    private void visitBlockInternal(
            final int localX,
            final int localY,
            final int localZ,
            final int paletteId,
            final BlockState state
    ) {
        blockApplyFailureCounters.recordVisitedInstruction();
        if (state == null) {
            blockApplyFailureCounters.recordNullState();
            return;
        }

        mutableWorldPos.set(chunkStartX + localX, localY, chunkStartZ + localZ);
        final BlockState previousState = tracePayloadWatches ? chunk.getBlockState(mutableWorldPos) : null;
        if (tracePayloadWatches) {
            PayloadWatchTracer.traceRestoreInstructionVisited(
                    chunk,
                    mutableWorldPos,
                    previousState,
                    state,
                    operationId,
                    BLOCK_TRACE_SOURCE
            );
        }

        if (!ChunkRestorer.applyBlockChange(
                chunk,
                chunkPosition,
                localX,
                localY,
                localZ,
                state,
                mutableWorldPos,
                previousState,
                sections,
                bottomY,
                topYInclusive,
                tracePayloadWatches,
                clearedToAir,
                blockApplyFailureCounters,
                operationId
        )) {
            if (tracePayloadWatches) {
                PayloadWatchTracer.traceRestoreBlockFailure(
                        world,
                        chunkPosition,
                        mutableWorldPos,
                        operationId,
                        "restore failed before block reached live world"
                );
            }
            return;
        }

        touchedSections[(localY - bottomY) >> 4] = true;
        copyBlockToRuntimeDelta(localX, localY, localZ, paletteId, state);
        blockApplyFailureCounters.recordAppliedBlock();
        appliedBlocksCount++;
        if (tracePayloadWatches) {
            PayloadWatchTracer.traceRestoredBlock(chunk, mutableWorldPos, state, operationId);
        }
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
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
            final String entityUuid = EntityPayloadNbt.findUuidString(nbt)
                    .orElse("<missing-uuid>");
            PayloadWatchTracer.traceRestoreEntitySkipped(
                    world,
                    chunkPosition,
                    entityUuid,
                    operationId,
                    "restore skipped: replayLegacyEntities=false"
            );
        }
    }

    /**
     * Checks if replayed entities cleanup operations should proceed.
     *
     * @param sourceDelta source loaded delta
     * @return true if cleanup is required
     */
    private boolean shouldCleanupReplayedEntities(
            final ChunkDelta<BlockState, NbtCompound> sourceDelta
    ) {
        return replayLegacyEntities
                && sourceDelta != null
                && sourceDelta.shouldSuppressInitialRepopulation();
    }

    /**
     * Resolves the coordinate boundaries matching the chunk column search space.
     *
     * @return Box mapping spatial dimensions
     */
    private Box chunkEntitySearchBox() {
        return ChunkEntityQueries.chunkColumnBox(world, chunkPosition);
    }

    /**
     * Replicates block changes in-place inside target runtime delta bounds.
     *
     * @param localX local X block offset
     * @param localY local Y block offset
     * @param localZ local Z block offset
     * @param state  block state data
     */
    private void copyBlockToRuntimeDelta(
            final int localX,
            final int localY,
            final int localZ,
            final int paletteId,
            final BlockState state
    ) {
        if (runtimeDelta != null) {
            if (paletteId >= 0) {
                runtimeDelta.appendDecodedBlock(localX, localY, localZ, paletteId);
            } else {
                runtimeDelta.appendSnapshotBlockChange(localX, localY, localZ, state);
            }
        }
    }

    /**
     * Restores a block entity from NBT when the current block state supports it.
     *
     * @param localX local coordinate X
     * @param localY local coordinate Y
     * @param localZ local coordinate Z
     * @param nbt    block entity NBT compound data
     */
    private void restoreBlockEntity(
            final int localX,
            final int localY,
            final int localZ,
            final NbtCompound nbt
    ) {
        mutableWorldPos.set(chunkStartX + localX, localY, chunkStartZ + localZ);
        final BlockState currentState = chunk.getBlockState(mutableWorldPos);

        if (!currentState.hasBlockEntity()) {
            if (tracePayloadWatches) {
                PayloadWatchTracer.traceRestoreBlockEntitySkipped(
                        world,
                        chunkPosition,
                        mutableWorldPos,
                        operationId,
                        "restore skipped: missing block state"
                );
            }
            return;
        }

        if (!isBlockEntityNbtCompatibleWithState(nbt, currentState)) {
            if (tracePayloadWatches) {
                PayloadWatchTracer.traceRestoreBlockEntitySkipped(
                        world,
                        chunkPosition,
                        mutableWorldPos,
                        operationId,
                        "restore skipped: block entity type incompatible with current block state"
                );
            }
            return;
        }

        final BlockEntity blockEntity = BlockEntity.createFromNbt(
                mutableWorldPos,
                currentState,
                nbt,
                world.getRegistryManager()
        );
        if (blockEntity == null) {
            LOGGER.warn("Failed to create block entity from NBT at {}", mutableWorldPos);
            if (tracePayloadWatches) {
                PayloadWatchTracer.traceRestoreBlockEntitySkipped(
                        world,
                        chunkPosition,
                        mutableWorldPos,
                        operationId,
                        "restore skipped: block entity could not be created from NBT"
                );
            }
            return;
        }

        chunk.removeBlockEntity(mutableWorldPos);
        chunk.addBlockEntity(blockEntity);
        if (runtimeDelta != null) {
            runtimeDelta.addBlockEntityData(localX, localY, localZ, nbt, false);
        }
        restoredBlockEntitiesCount++;
        if (tracePayloadWatches) {
            PayloadWatchTracer.traceRestoredBlockEntity(world, chunkPosition, mutableWorldPos, blockEntity, nbt,
                    operationId);
        }
    }
}
