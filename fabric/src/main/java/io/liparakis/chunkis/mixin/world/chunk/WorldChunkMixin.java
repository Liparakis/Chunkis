package io.liparakis.chunkis.mixin.world.chunk;

import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.mixin.accessor.ChunkBlockEntityNbtAccessor;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import io.liparakis.chunkis.world.restoration.capture.ChunkBlockEntityCapture;
import io.liparakis.chunkis.world.restoration.core.WorldChunkPromotion;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.ownership.OwnedDeltaAccess;
import io.liparakis.chunkis.world.tracking.settle.FreshChunkDuck;
import io.liparakis.chunkis.world.tracking.settle.GenerationSettling;
import io.liparakis.chunkis.world.tracking.settle.GenerationTickDuck;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.state.LeafTickContext;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.tick.BasicTickScheduler;
import net.minecraft.world.tick.ChunkTickScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks live chunk changes and restores saved CIS snapshots.
 */
@Mixin(WorldChunk.class)
public class WorldChunkMixin implements ChunkisMutationGuardDuck, FreshChunkDuck {

    /**
     * Whether this chunk was generated in this session; its post-processing then counts as generation settling.
     */
    @Unique
    private boolean chunkis$freshlyGenerated;

    /**
     * Trace source identification tag label.
     */
    @Unique
    private static final String SOURCE = "WorldChunkMixin";

    /**
     * Trace source identification tag label for restore.
     */
    @Unique
    private static final String RESTORE_SOURCE = "WorldChunkMixin#chunkis$restoreChunkFromDelta";

    /**
     * Trace source identification tag label for block state updates.
     */
    @Unique
    private static final String SET_BLOCK_STATE_SOURCE = "WorldChunkMixin#setBlockState";

    /**
     * Trace source identification tag label for block entity updates.
     */
    @Unique
    private static final String SET_BLOCK_ENTITY_SOURCE = "WorldChunkMixin#setBlockEntity";

    /**
     * Trace source identification tag label for block entity removal.
     */
    @Unique
    private static final String REMOVE_BLOCK_ENTITY_SOURCE = "WorldChunkMixin#removeBlockEntity";

    /**
     * Active mutation tracking scope instance.
     */
    @Unique
    private final ChunkMutationTrackingScope chunkis$mutationTrackingScope = new ChunkMutationTrackingScope();

    /**
     * Cached dedicated/integrated server thread used to reject off-thread chunk mutations.
     */
    @Unique
    private Thread chunkis$serverThread;

    /**
     * Stores loaded to world state.
     */
    @Shadow
    private boolean loadedToWorld;

    /**
     * Default constructor for WorldChunkMixin.
     */
    public WorldChunkMixin() {
    }

    /**
     * Returns whether a block transition should be ignored because it came from
     * vanilla's natural leaf-decay tick path rather than an intentional saved edit.
     *
     * <p>Only actual decay-to-air transitions are ignored. Scheduled leaf ticks
     * also rewrite the {@link LeavesBlock#DISTANCE} property, and those updates
     * must be tracked or restored leaves will keep stale decay distances.</p>
     *
     * @param previous preceding block state
     * @param next     succeeding block state
     * @return true if tick represents natural leaf decay
     */
    @Unique
    private static boolean chunkis$isNaturalLeafDecay(final BlockState previous, final BlockState next) {
        return chunkis$isNaturalLeafDecayTransition(
                LeafTickContext.isActive(),
                previous.getBlock() instanceof LeavesBlock,
                next.getBlock() instanceof LeavesBlock,
                next.isAir()
        );
    }

    /**
     * Resolves whether a leaf-tick transition represents true vanilla decay.
     *
     * <p>This helper keeps the classification rule testable without requiring a
     * bootstrapped Minecraft block registry inside plain unit tests.</p>
     *
     * @param leafTickActive   whether execution is inside a leaf tick context
     * @param previousIsLeaves whether the previous state was a leaf block
     * @param nextIsLeaves     whether the next state is a leaf block
     * @param nextIsAir        whether the next state is air
     * @return {@code true} when the transition is natural leaf decay
     */
    @Unique
    private static boolean chunkis$isNaturalLeafDecayTransition(
            final boolean leafTickActive,
            final boolean previousIsLeaves,
            final boolean nextIsLeaves,
            final boolean nextIsAir) {
        return leafTickActive && previousIsLeaves && !nextIsLeaves && nextIsAir;
    }

    /**
     * Returns whether vanilla post-processing should be given persisted block-entity NBT.
     */
    @Unique
    private static boolean chunkis$shouldSeedPostProcessingBlockEntities(
            final boolean hasLiveBlockEntities,
            final boolean hasPendingBlockEntityNbts,
            final boolean hasDelta,
            final boolean hasDeltaBlockEntities
    ) {
        return !hasLiveBlockEntities && !hasPendingBlockEntityNbts && hasDelta && hasDeltaBlockEntities;
    }

    /**
     * Captures live block-state mutations into the chunk's runtime delta.
     *
     * <p>This hook runs before vanilla applies the new state so the previous state
     * can still be inspected and no-op writes can be ignored. Restore-time writes,
     * client writes, and non-server-thread writes are filtered out by
     * {@link #chunkis$shouldNotTrackChunkMutation(WorldChunk)}.</p>
     *
     * @param pos   target block coordinates position
     * @param state target block state to set
     * @param flags update flags
     * @param cir   callback info returnable wrapper
     */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void chunkis$onSetBlockState(
            final BlockPos pos, final BlockState state, final int flags,
            final CallbackInfoReturnable<BlockState> cir) {
        final WorldChunk chunk = chunkis$self();
        final ChunkMutationTrackingScope.Cause suppressionCause = chunkis$getSuppressionCause(chunk);
        final ChunkDelta<BlockState, NbtCompound> existingDelta = chunkis$getBlockDelta();
        final int blockChangesBefore = existingDelta != null ? existingDelta.getBlockChangesCount() : 0;
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
        if (chunkis$isUnownedGenerationSettling(existingDelta)) {
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                OwnedDeltaAccess.getOrClaim(
                        chunk, ChunkTraceReason.PLAYER_OR_COMMAND_EDIT,
                        SET_BLOCK_STATE_SOURCE
                );
        final boolean becameDirty = !delta.isDirty();
        if (chunk.getWorld() instanceof ServerWorld serverWorld) {
            BaseChunkCaptureUtil.captureBaseChunkIfMissing(serverWorld, chunk, delta);
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
                delta.getBlockChangesCount(),
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
     *
     * @param pos   target block coordinates position
     * @param state target block state
     * @param flags update flags
     * @param cir   callback info returnable wrapper
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
     *
     * @param blockEntity block entity instance
     * @param ci          callback info helper
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
        if (!chunk.getBlockState(pos)
                .hasBlockEntity()) {
            return;
        }
        if (!(chunk.getWorld() instanceof ServerWorld serverWorld)
                || chunkis$isUnownedGenerationSettling(chunkis$getBlockDelta())) {
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                OwnedDeltaAccess.getOrClaim(
                        chunk, ChunkTraceReason.PLAYER_OR_COMMAND_EDIT,
                        SET_BLOCK_ENTITY_SOURCE
                );
        try {
            BaseChunkCaptureUtil.captureBaseChunkIfMissing(serverWorld, chunk, delta);
            delta.prepareForMutation(SET_BLOCK_ENTITY_SOURCE);
            ChunkBlockEntityCapture.captureBlockEntity(
                    blockEntity, serverWorld.getRegistryManager(),
                    delta
            );
            PayloadWatchTracer.traceDeltaStage(
                    serverWorld.getRegistryKey()
                            .getValue()
                            .toString(),
                    chunk.getPos(),
                    delta,
                    null,
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "set-block-entity-post-capture",
                    SET_BLOCK_ENTITY_SOURCE,
                    "delta state after setBlockEntity capture",
                    null
            );
            GlobalChunkTracker.markDirty(chunk, SET_BLOCK_ENTITY_SOURCE);
        } catch (final Exception e) {
            Chunkis.LOGGER.error("Chunkis: Failed to capture block entity at {}", pos, e);
        }
    }

    /**
     * Removes persisted block-entity data when vanilla removes a live block entity.
     *
     * @param pos block coordinates position to remove
     * @param ci  callback info helper
     */
    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void chunkis$onRemoveBlockEntity(final BlockPos pos, final CallbackInfo ci) {
        if (!this.loadedToWorld) {
            return;
        }
        final WorldChunk chunk = chunkis$self();
        final ChunkMutationTrackingScope.Cause suppressionCause = chunkis$getSuppressionCause(chunk);
        if (suppressionCause != ChunkMutationTrackingScope.Cause.NONE) {
            chunkis$traceSuppressedMutation(chunk, suppressionCause, REMOVE_BLOCK_ENTITY_SOURCE, pos);
            return;
        }
        if (chunkis$shouldNotTrackChunkMutation(chunk)
                || chunkis$isUnownedGenerationSettling(chunkis$getBlockDelta())) {
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                OwnedDeltaAccess.getOrClaim(
                        chunk, ChunkTraceReason.PLAYER_OR_COMMAND_EDIT,
                        REMOVE_BLOCK_ENTITY_SOURCE
                );
        delta.prepareForMutation(REMOVE_BLOCK_ENTITY_SOURCE);
        delta.removeBlockEntityData(
                pos.getX() & CisConstants.COORD_MASK, pos.getY(),
                pos.getZ() & CisConstants.COORD_MASK
        );
        if (chunk.getWorld() instanceof ServerWorld serverWorld) {
            PayloadWatchTracer.traceDeltaStage(
                    serverWorld.getRegistryKey()
                            .getValue()
                            .toString(),
                    chunk.getPos(),
                    delta,
                    null,
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "remove-block-entity-post-remove",
                    REMOVE_BLOCK_ENTITY_SOURCE,
                    "delta state after removeBlockEntity removal",
                    null
            );
        }
        GlobalChunkTracker.markDirty(chunk, REMOVE_BLOCK_ENTITY_SOURCE);
    }

    /**
     * Seeds vanilla's pending block-entity NBT map just before post-processing so
     * WorldChunk can materialize persisted block entities through its normal path.
     */
    @Inject(method = "runPostProcessing", at = @At("HEAD"))
    @SuppressWarnings("unchecked")
    private void chunkis$seedPostProcessingBlockEntities(
            final ServerWorld world,
            final CallbackInfo ci
    ) {
        final WorldChunk chunk = chunkis$self();
        final ChunkDelta<BlockState, NbtCompound> delta =
                chunk instanceof ChunkisDeltaDuck duck
                        ? (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta()
                        : null;
        final var pendingBlockEntityNbts = ((ChunkBlockEntityNbtAccessor) chunk).chunkis$getBlockEntityNbts();
        if (!chunkis$shouldSeedPostProcessingBlockEntities(
                !chunk.getBlockEntities()
                        .isEmpty(),
                !pendingBlockEntityNbts.isEmpty(),
                delta != null,
                delta != null && !delta.getBlockEntities()
                        .isEmpty()
        )) {
            return;
        }

        final int chunkStartX = chunk.getPos()
                .getStartX();
        final int chunkStartZ = chunk.getPos()
                .getStartZ();
        delta.getBlockEntities()
                .forEach((packedPos, nbt) -> {
                    if (nbt == null) {
                        return;
                    }
                    pendingBlockEntityNbts.put(
                            new BlockPos(
                                    chunkStartX + io.liparakis.chunkis.core.BlockInstruction.unpackX(packedPos),
                                    io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos),
                                    chunkStartZ + io.liparakis.chunkis.core.BlockInstruction.unpackZ(packedPos)
                            ),
                            nbt.copy()
                    );
                });
    }

    /**
     * Replays any proto-attached CIS snapshot once vanilla promotes a proto chunk
     * into a live world chunk.
     *
     * @param world        server world context
     * @param protoChunk   deserialized proto chunk
     * @param entityLoader entity loader instance
     * @param ci           callback info helper
     */
    @Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;" + "Lnet" +
            "/minecraft/world/chunk/WorldChunk$EntityLoader;)V", at = @At("RETURN"))
    private void chunkis$onConstructFromProto(
            final ServerWorld world, final ProtoChunk protoChunk,
            final WorldChunk.EntityLoader entityLoader, final CallbackInfo ci) {
        if (((FreshChunkDuck) protoChunk).chunkis$isFreshlyGenerated()) {
            chunkis$markFreshlyGenerated();
            final WorldChunk self = chunkis$self();
            chunkis$tagGenerationTicks(self.getBlockTickScheduler());
            chunkis$tagGenerationTicks(self.getFluidTickScheduler());
        }
        final ChunkDelta<BlockState, NbtCompound> protoDelta = WorldChunkPromotion.resolveProtoDelta(protoChunk);
        final String protoOperationId = protoChunk instanceof ChunkisDeltaDuck deltaDuck
                ? deltaDuck.chunkis$getRestoreOperationId()
                : null;
        if (protoDelta != null && protoOperationId != null) {
            PayloadWatchTracer.traceProtoDeltaPresentBeforeConversion(
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
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
        if (protoDelta == null || !ChunkDeltaOwnership.hasRestorableChunkisState(protoDelta)) {
            if (protoOperationId != null) {
                PayloadWatchTracer.traceWorldChunkDeltaMissing(
                        chunkis$self(),
                        protoOperationId,
                        SOURCE + "#chunkis$onConstructFromProto"
                );
            }
            PendingChunkMutationSuppression.end(
                    world.getRegistryKey(),
                    chunkis$self().getPos(),
                    SOURCE + "#chunkis$onConstructFromProto#empty"
            );
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
            WorldChunkPromotion.restoreChunkFromDelta(world, chunkis$self(), protoChunk, protoDelta);
        } finally {
            if (protoOperationId != null) {
                PayloadWatchTracer.traceProtoDeltaPresentAfterConversion(
                        world.getRegistryKey()
                                .getValue()
                                .toString(),
                        protoChunk,
                        protoDelta,
                        protoOperationId,
                        SOURCE + "#chunkis$onConstructFromProto"
                );
            }
            chunkis$mutationTrackingScope.pop(ChunkMutationTrackingScope.Cause.RESTORE);
            PendingChunkMutationSuppression.end(
                    world.getRegistryKey(),
                    chunkis$self().getPos(),
                    SOURCE + "#chunkis$onConstructFromProto#finally"
            );
        }
    }

    @Override
    public boolean chunkis$isFreshlyGenerated() {
        return chunkis$freshlyGenerated;
    }

    @Override
    public void chunkis$markFreshlyGenerated() {
        this.chunkis$freshlyGenerated = true;
    }

    /**
     * Runs a freshly generated chunk's post-processing as generation settling: regenerating the chunk replays it.
     *
     * @param world    server world
     * @param original vanilla post-processing
     */
    @WrapMethod(method = "runPostProcessing")
    private void chunkis$settleGeneratedChunk(final ServerWorld world, final Operation<Void> original) {
        GenerationSettling.run(chunkis$freshlyGenerated, () -> original.call(world));
    }

    /**
     * Marks the pending ticks of a freshly generated chunk as generation ticks.
     *
     * @param scheduler one of the chunk's tick schedulers
     */
    @Unique
    private static void chunkis$tagGenerationTicks(final BasicTickScheduler<?> scheduler) {
        if (scheduler instanceof ChunkTickScheduler<?> chunkScheduler) {
            GenerationSettling.tagGenerated((GenerationTickDuck) chunkScheduler, chunkScheduler.collectTicks(0L));
        }
    }

    /**
     * Returns whether a write is generation settling in a chunk Chunkis does not own: regenerating the chunk
     * replays it, so it must not claim the chunk.
     *
     * @param delta the chunk's current delta, or {@code null}
     * @return {@code true} when the write is left untracked
     */
    @Unique
    private static boolean chunkis$isUnownedGenerationSettling(final ChunkDelta<?, ?> delta) {
        return GenerationSettling.isActive() && !ChunkDeltaOwnership.hasChunkisOwnedState(delta);
    }

    /**
     * Returns whether live mutation tracking should ignore the current write.
     *
     * <p>Chunkis only tracks server-thread writes to fully realized server chunks.
     * Restore-time writes are also ignored to avoid marking a freshly loaded chunk
     * dirty just because its snapshot was applied.</p>
     *
     * @param chunk target world chunk to evaluate
     * @return true if mutation tracking should be bypassed
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
                    world.getRegistryKey()
                            .getValue()
                            .toString(), DebugChunkKeys.of(chunk.getPos()),
                    null, null, null, null
            );
            Chunkis.LOGGER.warn(
                    "Chunkis: Block change rejected outside server thread for chunk {} on thread {}",
                    chunk.getPos(),
                    Thread.currentThread()
                            .getName()
            );
            return true;
        }

        return false;
    }

    /**
     * Evaluates active mutation suppression causes.
     *
     * @param chunk target world chunk
     * @return current tracking suppression cause code
     */
    @Unique
    private ChunkMutationTrackingScope.Cause chunkis$getSuppressionCause(final WorldChunk chunk) {
        if (chunk instanceof ChunkisMutationGuardDuck guardDuck) {
            final ChunkMutationTrackingScope.Cause liveCause =
                    guardDuck.chunkis$getMutationTrackingScope()
                            .currentCause();
            if (liveCause != ChunkMutationTrackingScope.Cause.NONE) {
                return liveCause;
            }
        }
        return PendingChunkMutationSuppression.currentCause(chunk);
    }

    /**
     * Dispatches session traces mapping suppressed chunk mutations.
     *
     * @param chunk  target world chunk
     * @param cause  active tracking suppression cause code
     * @param source class/method trace source trigger label
     * @param pos    target block coordinates position, may be null
     */
    @Unique
    private void chunkis$traceSuppressedMutation(
            final WorldChunk chunk,
            final ChunkMutationTrackingScope.Cause cause,
            final String source,
            final BlockPos pos) {
        if (!ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO)) {
            return;
        }
        final ChunkMutationTrackingScope scope = chunkis$getMutationTrackingScope();
        final boolean shouldTrace = scope.currentCause() == cause
                ? scope.shouldTraceSuppression(cause)
                : PendingChunkMutationSuppression.shouldTrace(
                        chunk.getWorld()
                        .getRegistryKey(),
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
                        + cause.name()
                        .toLowerCase()
                        .replace('_', '-')
                        + (pos != null ? " at " + pos.toShortString() : ""),
                chunk.getWorld()
                        .getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(chunk.getPos()),
                null,
                null,
                false,
                null
        );
    }

    /**
     * Dispatches session traces when real edits are accepted onto chunks.
     *
     * @param chunk            target world chunk
     * @param pos              target block coordinates position
     * @param suppressionCause active tracking suppression cause code
     */
    @Unique
    private void chunkis$traceAcceptedRealEdit(
            final WorldChunk chunk,
            final BlockPos pos,
            final ChunkMutationTrackingScope.Cause suppressionCause) {
        // Runs on every accepted edit: build trace strings only when tracing is enabled.
        if (suppressionCause != ChunkMutationTrackingScope.Cause.NONE
                && ChunkisDebugConfig.allows(ChunkisDebugDomain.ASSERTIONS, ChunkTraceSeverity.ERROR)) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.PASSIVE_CHUNK_DIRTIED,
                    SET_BLOCK_STATE_SOURCE,
                    "passive context produced first dirty mutation at " + pos.toShortString(),
                    chunk.getWorld()
                            .getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunk.getPos()),
                    null,
                    null,
                    true,
                    null
            );
        }
        if (!ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO)) {
            return;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.MUTATION_ACCEPTED_REAL_EDIT,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SET_BLOCK_STATE_SOURCE,
                "accepted real chunk edit at " + pos.toShortString(),
                chunk.getWorld()
                        .getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(chunk.getPos()),
                null,
                null,
                true,
                null
        );
    }

    /**
     * Returns whether the caller is currently on the owning server thread.
     *
     * @param world context world reference
     * @return true if executing on server tick thread
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
     * {@inheritDoc}
     */
    @Override
    public ChunkMutationTrackingScope chunkis$getMutationTrackingScope() {
        return chunkis$mutationTrackingScope;
    }

    /**
     * Returns the live mutable Chunkis delta attached to this chunk.
     *
     * @return active block delta
     */
    @Unique
    @SuppressWarnings("unchecked")
    private ChunkDelta<BlockState, NbtCompound> chunkis$getBlockDelta() {
        return (ChunkDelta<BlockState, NbtCompound>) ((ChunkisDeltaDuck) this).chunkis$getDelta();
    }


    /**
     * Convenience cast for accessing the target {@link WorldChunk} instance.
     *
     * @return self casted to WorldChunk
     */
    @Unique
    private WorldChunk chunkis$self() {
        return (WorldChunk) (Object) this;
    }
}
