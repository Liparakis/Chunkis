package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkSectionDebugUtil;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.storage.BaseChunkCaptureUtil;
import io.liparakis.chunkis.storage.ChunkDeltaOwnership;
import io.liparakis.chunkis.storage.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.storage.model.CisConstants;
import io.liparakis.chunkis.world.ChunkBlockEntityCapture;
import io.liparakis.chunkis.world.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.ChunkRestorer;
import io.liparakis.chunkis.world.GlobalChunkTracker;
import io.liparakis.chunkis.world.LeafTickContext;
import io.liparakis.chunkis.world.PendingChunkMutationSuppression;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Tracks live chunk changes and restores saved CIS snapshots.
 */
@Mixin(WorldChunk.class)
public class WorldChunkMixin implements ChunkisMutationGuardDuck {

    @Unique
    private static final String SOURCE = "WorldChunkMixin";
    @Unique
    private static final String RESTORE_SOURCE = "WorldChunkMixin#chunkis$restoreChunkFromDelta";
    @Unique
    private static final String SET_BLOCK_STATE_SOURCE = "WorldChunkMixin#setBlockState";
    @Unique
    private static final String SET_BLOCK_ENTITY_SOURCE = "WorldChunkMixin#setBlockEntity";
    @Unique
    private static final String REMOVE_BLOCK_ENTITY_SOURCE = "WorldChunkMixin#removeBlockEntity";

    /**
     * Matches Nether portal POI entries already known to vanilla's POI storage.
     */
    @Unique
    private static final Predicate<RegistryEntry<PointOfInterestType>> PORTAL_POI_PREDICATE =
            type -> type.matchesKey(PointOfInterestTypes.NETHER_PORTAL);

    /**
     * Matches Nether portal blocks inside restored chunk sections.
     */
    @Unique
    private static final Predicate<BlockState> NETHER_PORTAL_BLOCK_PREDICATE =
            state -> state.isOf(Blocks.NETHER_PORTAL);

    @Unique
    private final ChunkMutationTrackingScope chunkis$mutationTrackingScope = new ChunkMutationTrackingScope();

    /**
     * Cached dedicated/integrated server thread used to reject off-thread chunk mutations.
     */
    @Unique
    private Thread chunkis$serverThread;

    /**
     * Scratch counter reused while rebuilding portal POIs after restore.
     */
    @Unique
    private int chunkis$portalBlockCount;

    /**
     * Captures live block-state mutations into the chunk's runtime delta.
     *
     * <p>This hook runs before vanilla applies the new state so the previous state
     * can still be inspected and no-op writes can be ignored. Restore-time writes,
     * client writes, and non-server-thread writes are filtered out by
     * {@link #chunkis$shouldNotTrackChunkMutation(WorldChunk)}.</p>
     */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void chunkis$onSetBlockState(
            final BlockPos pos, final BlockState state, final int flags,
            final CallbackInfoReturnable<BlockState> cir) {
        final WorldChunk chunk = chunkis$self();
        final ChunkMutationTrackingScope.Cause suppressionCause = chunkis$getSuppressionCause(chunk);
        final ChunkDelta<BlockState, NbtCompound> existingDelta = chunkis$getBlockDelta();
        final int blockChangesBefore = existingDelta != null ? existingDelta.getBlockInstructions().size() : 0;
        final long mutationGenerationBefore = existingDelta != null ? existingDelta.getMutationGeneration() : 0L;
        final boolean deltaExistedBefore = existingDelta != null;
        final BlockState previous = chunk.getBlockState(pos);
        if (suppressionCause != ChunkMutationTrackingScope.Cause.NONE) {
            PayloadWatchTracer.traceBlockSetStateEntered(
                    chunk,
                    pos,
                    previous,
                    state,
                    flags,
                    SET_BLOCK_STATE_SOURCE,
                    suppressionCause,
                    true,
                    false,
                    false,
                    blockChangesBefore,
                    blockChangesBefore,
                    mutationGenerationBefore,
                    "mutation suppressed before tracking"
            );
            chunkis$traceSuppressedMutation(chunk, suppressionCause, SET_BLOCK_STATE_SOURCE, pos);
            return;
        }
        if (chunkis$shouldNotTrackChunkMutation(chunk)) {
            PayloadWatchTracer.traceBlockSetStateEntered(
                    chunk,
                    pos,
                    previous,
                    state,
                    flags,
                    SET_BLOCK_STATE_SOURCE,
                    suppressionCause,
                    false,
                    false,
                    false,
                    blockChangesBefore,
                    blockChangesBefore,
                    mutationGenerationBefore,
                    "mutation skipped before tracking"
            );
            return;
        }
        if (previous == state || chunkis$isNaturalLeafDecay(previous, state)) {
            PayloadWatchTracer.traceBlockSetStateEntered(
                    chunk,
                    pos,
                    previous,
                    state,
                    flags,
                    SET_BLOCK_STATE_SOURCE,
                    suppressionCause,
                    false,
                    false,
                    false,
                    blockChangesBefore,
                    blockChangesBefore,
                    mutationGenerationBefore,
                    "mutation skipped because state was unchanged or natural leaf decay"
            );
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                chunkis$getOrCreateOwnedBlockDelta(chunk, ChunkTraceReason.PLAYER_OR_COMMAND_EDIT, SET_BLOCK_STATE_SOURCE);
        final boolean becameDirty = !delta.isDirty();
        if (chunk.getWorld() instanceof ServerWorld serverWorld) {
            BaseChunkCaptureUtil.captureBaseChunk(serverWorld, chunk, delta);
        }
        delta.prepareForMutation(SET_BLOCK_STATE_SOURCE);
        delta.addBlockChange(
                pos.getX() & CisConstants.COORD_MASK, pos.getY(), pos.getZ() & CisConstants.COORD_MASK,
                state
        );

        if (!state.hasBlockEntity()) {
            delta.removeBlockEntityData(
                    pos.getX() & CisConstants.COORD_MASK, pos.getY(),
                    pos.getZ() & CisConstants.COORD_MASK
            );
        }

        if (becameDirty) {
            chunkis$traceAcceptedRealEdit(chunk, pos, suppressionCause);
        }
        PayloadWatchTracer.traceBlockSetStateEntered(
                chunk,
                pos,
                previous,
                state,
                flags,
                SET_BLOCK_STATE_SOURCE,
                suppressionCause,
                false,
                true,
                !deltaExistedBefore,
                blockChangesBefore,
                delta.getBlockInstructions().size(),
                delta.getMutationGeneration(),
                "mutation recorded into ChunkDelta"
        );
        GlobalChunkTracker.markDirty(chunk, SET_BLOCK_STATE_SOURCE);
    }

    /**
     * Performs post-write bookkeeping after a block-state change completes.
     *
     * <p>Chunkis currently uses this to refresh the portal chunk index whenever a
     * Nether portal block may have been added or removed.</p>
     */
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void chunkis$afterSetBlockState(
            final BlockPos pos, final BlockState state, final int flags,
            final CallbackInfoReturnable<BlockState> cir) {
        final WorldChunk chunk = chunkis$self();
        if (chunkis$getSuppressionCause(chunk) != ChunkMutationTrackingScope.Cause.NONE
                || chunkis$shouldNotTrackChunkMutation(chunk)) {
            return;
        }

        final BlockState previous = cir.getReturnValue();
        final boolean portalChanged =
                (previous != null && previous.isOf(Blocks.NETHER_PORTAL)) || state.isOf(Blocks.NETHER_PORTAL);

        if (portalChanged && chunk.getWorld() instanceof ServerWorld serverWorld) {
            io.liparakis.chunkis.portal.PortalChunkIndexManager.updateChunk(serverWorld, chunk);
        }
    }

    /**
     * Captures serialized block-entity state after vanilla installs a block entity.
     *
     * <p>The hook runs on {@code RETURN} so the block entity is fully initialized
     * and can serialize itself through the normal registry-aware path.</p>
     */
    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void chunkis$onSetBlockEntity(final BlockEntity blockEntity, final CallbackInfo ci) {
        final WorldChunk chunk = chunkis$self();
        final ChunkMutationTrackingScope.Cause suppressionCause = chunkis$getSuppressionCause(chunk);
        if (suppressionCause != ChunkMutationTrackingScope.Cause.NONE) {
            chunkis$traceSuppressedMutation(chunk, suppressionCause, SET_BLOCK_ENTITY_SOURCE, blockEntity.getPos());
            return;
        }
        if (chunkis$shouldNotTrackChunkMutation(chunk)) {
            return;
        }

        final BlockPos pos = blockEntity.getPos();
        if (!chunk.getBlockState(pos).hasBlockEntity()) {
            return;
        }
        if (!(chunk.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                chunkis$getOrCreateOwnedBlockDelta(chunk, ChunkTraceReason.PLAYER_OR_COMMAND_EDIT, SET_BLOCK_ENTITY_SOURCE);
        try {
            BaseChunkCaptureUtil.captureBaseChunk(serverWorld, chunk, delta);
            delta.prepareForMutation(SET_BLOCK_ENTITY_SOURCE);
            ChunkBlockEntityCapture.captureBlockEntity(
                    blockEntity, serverWorld.getRegistryManager(),
                    delta
            );
            GlobalChunkTracker.markDirty(chunk, SET_BLOCK_ENTITY_SOURCE);
        } catch (final Exception e) {
            Chunkis.LOGGER.error("Chunkis: Failed to capture block entity at {}", pos, e);
        }
    }

    /**
     * Removes persisted block-entity data when vanilla removes a live block entity.
     */
    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void chunkis$onRemoveBlockEntity(final BlockPos pos, final CallbackInfo ci) {
        final WorldChunk chunk = chunkis$self();
        final ChunkMutationTrackingScope.Cause suppressionCause = chunkis$getSuppressionCause(chunk);
        if (suppressionCause != ChunkMutationTrackingScope.Cause.NONE) {
            chunkis$traceSuppressedMutation(chunk, suppressionCause, REMOVE_BLOCK_ENTITY_SOURCE, pos);
            return;
        }
        if (chunkis$shouldNotTrackChunkMutation(chunk)) {
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                chunkis$getOrCreateOwnedBlockDelta(chunk, ChunkTraceReason.PLAYER_OR_COMMAND_EDIT, REMOVE_BLOCK_ENTITY_SOURCE);
        delta.prepareForMutation(REMOVE_BLOCK_ENTITY_SOURCE);
        delta.removeBlockEntityData(
                pos.getX() & CisConstants.COORD_MASK, pos.getY(),
                pos.getZ() & CisConstants.COORD_MASK
        );
        GlobalChunkTracker.markDirty(chunk, REMOVE_BLOCK_ENTITY_SOURCE);
    }

    /**
     * Replays any proto-attached CIS snapshot once vanilla promotes a proto chunk
     * into a live world chunk.
     */
    @Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;" + "Lnet" +
            "/minecraft/world/chunk/WorldChunk$EntityLoader;)V", at = @At("RETURN"))
    private void chunkis$onConstructFromProto(
            final ServerWorld world, final ProtoChunk protoChunk,
            final WorldChunk.EntityLoader entityLoader, final CallbackInfo ci) {
        final ChunkDelta<BlockState, NbtCompound> protoDelta = chunkis$resolveProtoDelta(protoChunk);
        final String protoOperationId = protoChunk instanceof ChunkisDeltaDuck deltaDuck
                ? deltaDuck.chunkis$getRestoreOperationId()
                : null;
        if (protoDelta != null && protoOperationId != null) {
            PayloadWatchTracer.traceProtoDeltaPresentBeforeConversion(
                    world.getRegistryKey().getValue().toString(),
                    protoChunk,
                    protoDelta,
                    protoOperationId,
                    SOURCE + "#chunkis$onConstructFromProto"
            );
            PayloadWatchTracer.traceWorldChunkConstructorConsumed(
                    chunkis$self(),
                    protoDelta,
                    protoOperationId,
                    SOURCE + "#chunkis$onConstructFromProto"
            );
        }
        if (protoDelta == null || protoDelta.isEmpty()) {
            if (protoOperationId != null) {
                PayloadWatchTracer.traceWorldChunkDeltaMissing(
                        chunkis$self(),
                        protoOperationId,
                        SOURCE + "#chunkis$onConstructFromProto"
                );
            }
            PendingChunkMutationSuppression.end(world.getRegistryKey(), chunkis$self().getPos());
            return;
        }
        ChunkOwnershipTraceHelper.traceDecision(
                world.getRegistryKey(),
                chunkis$self().getPos(),
                "CLAIMED",
                ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE,
                RESTORE_SOURCE,
                protoDelta,
                ChunkMutationTrackingScope.Cause.RESTORE
        );
        chunkis$mutationTrackingScope.push(ChunkMutationTrackingScope.Cause.RESTORE);
        try {
            chunkis$restoreChunkFromDelta(world, chunkis$self(), protoChunk, protoDelta);
        } finally {
            if (protoDelta != null && protoOperationId != null) {
                PayloadWatchTracer.traceProtoDeltaPresentAfterConversion(
                        world.getRegistryKey().getValue().toString(),
                        protoChunk,
                        protoDelta,
                        protoOperationId,
                        SOURCE + "#chunkis$onConstructFromProto"
                );
            }
            chunkis$mutationTrackingScope.pop(ChunkMutationTrackingScope.Cause.RESTORE);
            PendingChunkMutationSuppression.end(world.getRegistryKey(), chunkis$self().getPos());
        }
    }

    /**
     * Returns whether live mutation tracking should ignore the current write.
     *
     * <p>Chunkis only tracks server-thread writes to fully realized server chunks.
     * Restore-time writes are also ignored to avoid marking a freshly loaded chunk
     * dirty just because its snapshot was applied.</p>
     */
    @Unique
    private boolean chunkis$shouldNotTrackChunkMutation(final WorldChunk chunk) {
        final World world = chunk.getWorld();
        if (world.isClient() || !ChunkStatus.FULL.equals(chunk.getStatus())) {
            return true;
        }

        if (!chunkis$isOnServerThread(world)) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS, ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR, ChunkTraceReason.OFF_THREAD_MUTATION_REJECTED, SOURCE +
                            "#chunkis$shouldNotTrackChunkMutation", "rejected mutation outside server thread",
                    world.getRegistryKey().getValue().toString(), new DebugChunkKey(
                            chunk.getPos().x,
                            chunk.getPos().z
                    ), null, null, null, null
            );
            Chunkis.LOGGER.warn(
                    "Chunkis: Block change rejected outside server thread for chunk {} on thread {}",
                    chunk.getPos(), Thread.currentThread().getName()
            );
            return true;
        }

        return false;
    }

    @Unique
    private ChunkMutationTrackingScope.Cause chunkis$getSuppressionCause(final WorldChunk chunk) {
        if (chunk instanceof ChunkisMutationGuardDuck guardDuck) {
            final ChunkMutationTrackingScope.Cause liveCause =
                    guardDuck.chunkis$getMutationTrackingScope().currentCause();
            if (liveCause != ChunkMutationTrackingScope.Cause.NONE) {
                return liveCause;
            }
        }
        final ChunkMutationTrackingScope.Cause pendingCause = PendingChunkMutationSuppression.currentCause(chunk);
        if (pendingCause == ChunkMutationTrackingScope.Cause.BASE_APPLY
                && ChunkStatus.FULL.equals(chunk.getStatus())
                && chunkis$isOnServerThread(chunk.getWorld())) {
            chunkis$clearLeakedPendingSuppression(chunk, pendingCause);
            return ChunkMutationTrackingScope.Cause.NONE;
        }
        return pendingCause;
    }

    @Unique
    private void chunkis$clearLeakedPendingSuppression(
            final WorldChunk chunk,
            final ChunkMutationTrackingScope.Cause cause
    ) {
        PendingChunkMutationSuppression.end(chunk.getWorld().getRegistryKey(), chunk.getPos());
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.SUPPRESSION_CONTEXT_LEAK,
                SOURCE + "#chunkis$getSuppressionCause",
                "cleared leaked " + cause.name().toLowerCase().replace('_', '-')
                        + " suppression on live chunk"
                        + " status=" + chunk.getStatus()
                        + " thread=" + Thread.currentThread().getName(),
                chunk.getWorld().getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                null,
                null,
                null,
                null
        );
    }

    @Unique
    private void chunkis$traceSuppressedMutation(
            final WorldChunk chunk,
            final ChunkMutationTrackingScope.Cause cause,
            final String source,
            final BlockPos pos) {
        final ChunkMutationTrackingScope scope = chunkis$getMutationTrackingScope();
        final boolean shouldTrace = scope.currentCause() == cause
                ? scope.shouldTraceSuppression(cause)
                : PendingChunkMutationSuppression.shouldTrace(
                chunk.getWorld().getRegistryKey(),
                chunk.getPos(),
                cause
        );
        if (!shouldTrace) {
            return;
        }

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkMutationTrackingScope.suppressionEventType(cause),
                ChunkTraceSeverity.INFO,
                cause == ChunkMutationTrackingScope.Cause.PASSIVE_LOAD
                        ? ChunkTraceReason.PASSIVE_CHUNK_DIRTIED
                        : ChunkTraceReason.NONE,
                source,
                "suppressed chunk mutation ownership during "
                        + cause.name().toLowerCase().replace('_', '-')
                        + (pos != null ? " at " + pos.toShortString() : ""),
                chunk.getWorld().getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                null,
                null,
                false,
                null
        );
    }

    @Unique
    private void chunkis$traceAcceptedRealEdit(
            final WorldChunk chunk,
            final BlockPos pos,
            final ChunkMutationTrackingScope.Cause suppressionCause) {
        if (suppressionCause != ChunkMutationTrackingScope.Cause.NONE) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.PASSIVE_CHUNK_DIRTIED,
                    SET_BLOCK_STATE_SOURCE,
                    "passive context produced first dirty mutation at " + pos.toShortString(),
                    chunk.getWorld().getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                    null,
                    null,
                    true,
                    null
            );
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.MUTATION_ACCEPTED_REAL_EDIT,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SET_BLOCK_STATE_SOURCE,
                "accepted real chunk edit at " + pos.toShortString(),
                chunk.getWorld().getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                null,
                null,
                true,
                null
        );
    }

    /**
     * Returns whether a block transition should be ignored because it came from
     * vanilla's natural leaf-decay tick path rather than an intentional saved edit.
     */
    @Unique
    private static boolean chunkis$isNaturalLeafDecay(final BlockState previous, final BlockState next) {
        return LeafTickContext.isActive() && (previous.getBlock() instanceof LeavesBlock || next.getBlock() instanceof LeavesBlock);
    }

    /**
     * Returns whether the caller is currently on the owning server thread.
     */
    @Unique
    private boolean chunkis$isOnServerThread(final World world) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        if (chunkis$serverThread == null) {
            final var server = serverWorld.getServer();
            if (server == null) {
                return false;
            }
            chunkis$serverThread = server.getThread();
        }

        return chunkis$serverThread == Thread.currentThread();
    }

    /**
     * Extracts the CIS delta previously attached to a proto chunk during
     * deserialization.
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> chunkis$resolveProtoDelta(final ProtoChunk proto) {
        return proto instanceof ChunkisDeltaDuck duck ?
                (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta() : null;
    }

    /**
     * Restores a promoted world chunk from the proto-carried CIS snapshot.
     */
    @Unique
    private void chunkis$restoreChunkFromDelta(
            final ServerWorld world, final WorldChunk chunk,
            final ProtoChunk proto,
            final ChunkDelta<BlockState, NbtCompound> protoDelta) {
        final ChunkDelta<BlockState, NbtCompound> selfDelta =
                chunkis$getOrCreateOwnedBlockDelta(chunk, ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE, RESTORE_SOURCE);
        final String operationId = chunkis$takeRestoreOperationId((ChunkisDeltaDuck) proto);
        if (selfDelta != null) {
            PayloadWatchTracer.traceWorldChunkDeltaAttached(chunk, selfDelta, operationId, RESTORE_SOURCE);
        } else {
            PayloadWatchTracer.traceWorldChunkDeltaMissing(chunk, operationId, RESTORE_SOURCE);
        }
        boolean coreRestoreCompleted = false;
        String failedStage = "chunk-restore";
        selfDelta.setSuppressInitialRepopulation(protoDelta.shouldSuppressInitialRepopulation());
        selfDelta.setChunkMetadata(protoDelta.getChunkMetadata(), false);
        chunkis$traceLiveDeltaMetadata(world, chunk, protoDelta, selfDelta, operationId);
        final boolean hasPersistedBaseChunk =
                io.liparakis.chunkis.storage.CisNbtUtil.hasPersistedBaseChunkNbt(protoDelta.getChunkMetadata());

        if (hasPersistedBaseChunk) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.BASE_SNAPSHOT_APPLY_STARTED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    RESTORE_SOURCE,
                    "world chunk before sparse replay: " + ChunkSectionDebugUtil.summarize(chunk),
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null
            );
        }

        try {
            ChunkRestorer.restore(world, chunk, protoDelta, selfDelta, operationId);
            coreRestoreCompleted = true;
            PayloadWatchTracer.traceRestoreSkipped(
                    chunk,
                    protoDelta,
                    operationId,
                    RESTORE_SOURCE
            );
            PayloadWatchTracer.traceLiveChunkState(
                    chunk,
                    ChunkTraceEventType.WATCH_PRESENT_AFTER_RESTORE,
                    "restore-live",
                    RESTORE_SOURCE,
                    operationId,
                    protoDelta
            );
            PayloadWatchTracer.traceLiveChunkState(
                    chunk,
                    ChunkTraceEventType.WATCH_LIVE_CHUNK_STATE_AFTER_RESTORE,
                    "after-restore-live-chunk",
                    RESTORE_SOURCE,
                    operationId,
                    protoDelta
            );
            failedStage = "portal-poi-resync";
            chunkis$resyncPortalPointOfInterestStorage(world, chunk);
            failedStage = "portal-index-update";
            io.liparakis.chunkis.portal.PortalChunkIndexManager.updateChunk(world, chunk);
        } catch (final Exception e) {
            if (coreRestoreCompleted) {
                chunkis$tracePostRestoreFailure(
                        world.getRegistryKey().getValue().toString(),
                        new DebugChunkKey(chunk.getPos().x, chunk.getPos().z), operationId, failedStage
                );
            }
            Chunkis.LOGGER.error("Chunkis: Failed to restore chunk {}", proto.getPos(), e);
        }

        if (hasPersistedBaseChunk) {
            final int finalSections = ChunkSectionDebugUtil.countNonEmptySections(chunk);
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    finalSections > 0
                            ? ChunkTraceEventType.BASE_SNAPSHOT_APPLY_COMPLETED
                            : ChunkTraceEventType.BASE_SNAPSHOT_APPLY_FAILED,
                    finalSections > 0 ? ChunkTraceSeverity.INFO : ChunkTraceSeverity.ERROR,
                    finalSections > 0 ? ChunkTraceReason.NONE : ChunkTraceReason.BASE_SNAPSHOT_NOT_APPLIED,
                    RESTORE_SOURCE,
                    "world chunk after sparse replay: " + ChunkSectionDebugUtil.summarize(chunk),
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null
            );
            if (finalSections == 0) {
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.ASSERTIONS,
                        ChunkTraceEventType.ASSERTION_FAILED,
                        ChunkTraceSeverity.ERROR,
                        ChunkTraceReason.BASE_SNAPSHOT_NOT_APPLIED,
                        RESTORE_SOURCE,
                        "persisted base NBT existed but final server chunk had zero non-empty sections",
                        world.getRegistryKey().getValue().toString(),
                        new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                        null,
                        operationId,
                        protoDelta.isDirty(),
                        null
                );
            }
        }

        if (chunkis$shouldMarkRestoredDeltaSaved((ChunkisDeltaDuck) proto, protoDelta)) {
            protoDelta.markSaved();
        }
    }

    @Unique
    private void chunkis$traceLiveDeltaMetadata(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> liveDelta,
            final String operationId
    ) {
        final boolean protoHasBase = io.liparakis.chunkis.storage.CisNbtUtil.hasPersistedBaseChunkNbt(protoDelta.getChunkMetadata());
        final boolean liveHasBase = io.liparakis.chunkis.storage.CisNbtUtil.hasPersistedBaseChunkNbt(liveDelta.getChunkMetadata());
        final boolean protoHasAnchor = io.liparakis.chunkis.storage.ChunkDeltaOwnership.hasChunkisPersistenceAnchor(protoDelta);
        final boolean liveHasAnchor = io.liparakis.chunkis.storage.ChunkDeltaOwnership.hasChunkisPersistenceAnchor(liveDelta);
        final boolean lostAnchor = protoHasAnchor && !liveHasAnchor;

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                lostAnchor
                        ? ChunkTraceEventType.BASE_METADATA_LOST_DURING_RESTORE
                        : ChunkTraceEventType.BASE_METADATA_ATTACHED_TO_LIVE_DELTA,
                lostAnchor ? ChunkTraceSeverity.ERROR : ChunkTraceSeverity.INFO,
                lostAnchor ? ChunkTraceReason.RESTORE_EXCEPTION : ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "live delta metadata after attach: proto=" + io.liparakis.chunkis.storage.DeltaPersistenceGuard.describeLifecycleState(protoDelta)
                        + ", live=" + io.liparakis.chunkis.storage.DeltaPersistenceGuard.describeLifecycleState(liveDelta)
                        + ", protoHasBase=" + protoHasBase
                        + ", liveHasBase=" + liveHasBase,
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                null,
                operationId,
                liveDelta.isDirty(),
                null
        );
    }

    @Unique
    private static String chunkis$takeRestoreOperationId(final ChunkisDeltaDuck deltaDuck) {
        final String operationId = deltaDuck.chunkis$getRestoreOperationId();
        if (operationId != null) {
            deltaDuck.chunkis$setRestoreOperationId(null);
            return operationId;
        }
        return ChunkTraceStore.nextOperationId("restore");
    }

    @Unique
    private static boolean chunkis$shouldMarkRestoredDeltaSaved(
            final ChunkisDeltaDuck deltaDuck,
            final ChunkDelta<?, ?> delta
    ) {
        return deltaDuck.chunkis$wasRestoreLoadedFromStorage() || !delta.isDirty();
    }

    @Unique
    private static void chunkis$tracePostRestoreFailure(
            final String worldId, final DebugChunkKey chunkKey,
            final String operationId, final String failedStage) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.RESTORE_FAILED,
                ChunkTraceSeverity.ERROR, ChunkTraceReason.RESTORE_EXCEPTION, RESTORE_SOURCE, "post-restore follow-up" +
                        " failed during " + failedStage, worldId, chunkKey, null, operationId, null, null
        );
    }

    /**
     * Rebuilds Nether portal POIs after snapshot restoration.
     *
     * <p>Portal blocks are exact chunk content, but POI storage is auxiliary
     * metadata. Re-adding the POIs here keeps portal search behavior aligned with
     * the restored block grid.</p>
     */
    @Unique
    private void chunkis$resyncPortalPointOfInterestStorage(final ServerWorld world, final WorldChunk chunk) {
        final PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        final RegistryEntry<PointOfInterestType> portalType =
                world.getRegistryManager().getOrThrow(RegistryKeys.POINT_OF_INTEREST_TYPE).getOrThrow(PointOfInterestTypes.NETHER_PORTAL);

        final int blockCount = chunkis$addPortalPois(chunk, poiStorage, portalType);
        if (blockCount == 0) {
            return;
        }

        final long poiCount = poiStorage.getInChunk(
                PORTAL_POI_PREDICATE, chunk.getPos(),
                PointOfInterestStorage.OccupationStatus.ANY
        ).count();

        if (poiCount == 0) {
            Chunkis.LOGGER.warn(
                    "Chunkis [PORTAL]: Restored chunk {} in {} has {} portal block(s) but no portal POIs " +
                            "after resync", chunk.getPos(), world.getRegistryKey().getValue(), blockCount
            );
        }
    }

    /**
     * Adds portal POIs for every restored Nether portal block in the chunk.
     */
    @Unique
    private int chunkis$addPortalPois(
            final WorldChunk chunk, final PointOfInterestStorage poiStorage,
            final RegistryEntry<PointOfInterestType> portalType) {
        chunkis$portalBlockCount = 0;
        chunk.forEachBlockMatchingPredicate(
                NETHER_PORTAL_BLOCK_PREDICATE, (pos, state) -> {
                    chunkis$portalBlockCount++;
                    poiStorage.add(pos, portalType);
                }
        );
        final int count = chunkis$portalBlockCount;
        chunkis$portalBlockCount = 0;
        return count;
    }

    @Override
    public ChunkMutationTrackingScope chunkis$getMutationTrackingScope() {
        return chunkis$mutationTrackingScope;
    }

    /**
     * Returns the live mutable Chunkis delta attached to this chunk.
     */
    @Unique
    @SuppressWarnings("unchecked")
    private ChunkDelta<BlockState, NbtCompound> chunkis$getBlockDelta() {
        return (ChunkDelta<BlockState, NbtCompound>) ((ChunkisDeltaDuck) this).chunkis$getDelta();
    }

    @Unique
    private ChunkDelta<BlockState, NbtCompound> chunkis$getOrCreateOwnedBlockDelta(
            final WorldChunk chunk,
            final ChunkTraceReason ownershipReason,
            final String source
    ) {
        ChunkDelta<BlockState, NbtCompound> delta = chunkis$getBlockDelta();
        if (delta == null) {
            delta = new ChunkDelta<>(BlockState::isAir);
            ((ChunkisDeltaDuck) chunk).chunkis$setDelta(delta);
            ChunkOwnershipTraceHelper.traceDecision(
                    chunk.getWorld().getRegistryKey(),
                    chunk.getPos(),
                    "CLAIMED",
                    ownershipReason,
                    source + "#createDelta",
                    delta,
                    chunkis$getSuppressionCause(chunk)
            );
        }
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            ChunkOwnershipTraceHelper.claimOwnership(delta, ownershipReason, source);
            ChunkOwnershipTraceHelper.traceDecision(
                    chunk.getWorld().getRegistryKey(),
                    chunk.getPos(),
                    "CLAIMED",
                    ownershipReason,
                    source,
                    delta,
                    chunkis$getSuppressionCause(chunk)
            );
        }
        return delta;
    }

    /**
     * Convenience cast for accessing the target {@link WorldChunk} instance.
     */
    @Unique
    private WorldChunk chunkis$self() {
        return (WorldChunk) (Object) this;
    }
}
