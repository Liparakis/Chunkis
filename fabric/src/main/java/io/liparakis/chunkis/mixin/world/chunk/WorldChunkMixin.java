package io.liparakis.chunkis.mixin.world.chunk;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.ChunkSectionDebugUtil;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.mixin.accessor.ChunkBlockEntityNbtAccessor;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityNbtCapture;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import io.liparakis.chunkis.world.restoration.capture.ChunkBlockEntityCapture;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.state.LeafTickContext;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.poi.PointOfInterestTypes;
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
public class WorldChunkMixin implements ChunkisMutationGuardDuck {

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
     * Trace source identification tag label for entity addition.
     */
    @Unique
    private static final String ADD_ENTITY_SOURCE = "WorldChunkMixin#addEntity";

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
     * Scratch counter reused while rebuilding portal POIs after restore.
     */
    @Unique
    private int chunkis$portalBlockCount;

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
     * Extracts the CIS delta previously attached to a proto chunk during deserialization.
     *
     * @param proto proto chunk to query
     * @return resolved block delta, or null
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> chunkis$resolveProtoDelta(final ProtoChunk proto) {
        return proto instanceof ChunkisDeltaDuck duck ?
                (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta() : null;
    }

    /**
     * Extracts the restore operation ID mapping session details.
     *
     * @param deltaDuck duck mapping instance
     * @return resolved operation ID string
     */
    @Unique
    private static String chunkis$takeRestoreOperationId(final ChunkisDeltaDuck deltaDuck) {
        final String operationId = deltaDuck.chunkis$getRestoreOperationId();
        if (operationId != null) {
            deltaDuck.chunkis$setRestoreOperationId(null);
            return operationId;
        }
        return ChunkTraceStore.nextOperationId("restore");
    }

    /**
     * Checks if the restored delta state should be marked as saved.
     *
     * @param deltaDuck duck mapping instance
     * @param delta     delta mapping instance
     * @return true if delta should be marked saved
     */
    @Unique
    private static boolean chunkis$shouldMarkRestoredDeltaSaved(
            final ChunkisDeltaDuck deltaDuck,
            final ChunkDelta<?, ?> delta
    ) {
        return deltaDuck.chunkis$wasRestoreLoadedFromStorage() || !delta.isDirty();
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
     * Traces session log details mapping failed restore execution paths.
     *
     * @param worldId     target world registry ID string
     * @param chunkKey    key mapping coordinates
     * @param operationId active load/save operation ID
     * @param failedStage failed stage description string
     */
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

        final ChunkDelta<BlockState, NbtCompound> delta =
                chunkis$getOrCreateOwnedBlockDelta(
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
        if (!(chunk.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                chunkis$getOrCreateOwnedBlockDelta(
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
        if (chunkis$shouldNotTrackChunkMutation(chunk)) {
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                chunkis$getOrCreateOwnedBlockDelta(
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
     * Captures entity loading operations mapping updates.
     *
     * @param entity target loaded entity
     * @param ci     callback info helper
     */
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void chunkis$onAddEntity(final Entity entity, final CallbackInfo ci) {
        final WorldChunk chunk = chunkis$self();
        final ChunkMutationTrackingScope.Cause suppressionCause = chunkis$getSuppressionCause(chunk);
        if (suppressionCause != ChunkMutationTrackingScope.Cause.NONE
                || chunkis$shouldNotTrackChunkMutation(chunk)
                || entity instanceof PlayerEntity
                || !entity.isAlive()
                || !entity.getChunkPos()
                .equals(chunk.getPos())) {
            return;
        }

        final NbtCompound entityNbt = ChunkEntityNbtCapture.serializeEntityNbt(entity);
        if (entityNbt == null || entityNbt.isEmpty()) {
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                chunkis$getOrCreateOwnedBlockDelta(chunk, ChunkTraceReason.PLAYER_OR_COMMAND_EDIT, ADD_ENTITY_SOURCE);
        if (chunk.getWorld() instanceof ServerWorld serverWorld) {
            BaseChunkCaptureUtil.captureBaseChunkIfMissing(serverWorld, chunk, delta);
        }
        delta.prepareForMutation(ADD_ENTITY_SOURCE);
        delta.removeEntitiesMatching(nbt -> nbt != null
                && nbt.getIntArray("UUID")
                .map(Uuids::toUuid)
                .map(entity.getUuid()::equals)
                .orElse(false));
        delta.putEntity(entity.getId(), entityNbt);
        GlobalChunkTracker.markDirty(chunk, ADD_ENTITY_SOURCE);
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
        final ChunkDelta<BlockState, NbtCompound> protoDelta = chunkis$resolveProtoDelta(protoChunk);
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
            chunkis$restoreChunkFromDelta(world, chunkis$self(), protoChunk, protoDelta);
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
        if (suppressionCause != ChunkMutationTrackingScope.Cause.NONE) {
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
     * Restores a promoted world chunk from the proto-carried CIS snapshot.
     *
     * @param world      server world context
     * @param chunk      target world chunk being populated
     * @param proto      source proto chunk instance
     * @param protoDelta source proto delta containing snapshot NBT
     */
    @Unique
    private void chunkis$restoreChunkFromDelta(
            final ServerWorld world, final WorldChunk chunk,
            final ProtoChunk proto,
            final ChunkDelta<BlockState, NbtCompound> protoDelta) {
        final ChunkDelta<BlockState, NbtCompound> selfDelta =
                chunkis$getOrCreateOwnedBlockDelta(
                        chunk, ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE,
                        RESTORE_SOURCE
                );
        final String operationId = chunkis$takeRestoreOperationId((ChunkisDeltaDuck) proto);
        PayloadWatchTracer.traceWorldChunkDeltaAttached(chunk, selfDelta, operationId, RESTORE_SOURCE);
        boolean coreRestoreCompleted = false;
        String failedStage = "chunk-restore";
        selfDelta.setSuppressInitialRepopulation(protoDelta.shouldSuppressInitialRepopulation());
        selfDelta.setChunkMetadata(protoDelta.getChunkMetadata(), false);
        chunkis$traceLiveDeltaMetadata(world, chunk, protoDelta, selfDelta, operationId);
        final boolean usePersistedBaseChunkForBlocks =
                io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(
                        protoDelta.getChunkMetadata()
                );

        if (usePersistedBaseChunkForBlocks) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.BASE_SNAPSHOT_APPLY_STARTED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    RESTORE_SOURCE,
                    "world chunk before sparse replay: " + ChunkSectionDebugUtil.summarize(chunk),
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunk.getPos()),
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
                        world.getRegistryKey()
                                .getValue()
                                .toString(),
                        DebugChunkKeys.of(chunk.getPos()), operationId, failedStage
                );
            }
            Chunkis.LOGGER.error("Chunkis: Failed to restore chunk {}", proto.getPos(), e);
        }

        if (usePersistedBaseChunkForBlocks) {
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
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(chunk.getPos()),
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
                        world.getRegistryKey()
                                .getValue()
                                .toString(),
                        DebugChunkKeys.of(chunk.getPos()),
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

    /**
     * Traces active metadata descriptors linked to live chunk deltas.
     *
     * @param world       server world context
     * @param chunk       target world chunk reference
     * @param protoDelta  proto delta containing source values
     * @param liveDelta   live delta mapping targets
     * @param operationId active load/save operation ID
     */
    @Unique
    private void chunkis$traceLiveDeltaMetadata(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> liveDelta,
            final String operationId
    ) {
        final boolean protoHasBase =
                io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil.hasPersistedBaseChunkNbt(protoDelta.getChunkMetadata());
        final boolean liveHasBase =
                io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil.hasPersistedBaseChunkNbt(liveDelta.getChunkMetadata());
        final boolean protoHasAnchor =
                io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership.hasChunkisPersistenceAnchor(protoDelta);
        final boolean liveHasAnchor =
                io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership.hasChunkisPersistenceAnchor(liveDelta);
        final boolean lostAnchor = protoHasAnchor && !liveHasAnchor;

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                lostAnchor
                        ? ChunkTraceEventType.BASE_METADATA_LOST_DURING_RESTORE
                        : ChunkTraceEventType.BASE_METADATA_ATTACHED_TO_LIVE_DELTA,
                lostAnchor ? ChunkTraceSeverity.ERROR : ChunkTraceSeverity.INFO,
                lostAnchor ? ChunkTraceReason.RESTORE_EXCEPTION : ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "live delta metadata after attach: proto="
                        + io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard.describeLifecycleState(
                        protoDelta)
                        + ", live="
                        + io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard.describeLifecycleState(
                        liveDelta)
                        + ", protoHasBase=" + protoHasBase
                        + ", liveHasBase=" + liveHasBase,
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(chunk.getPos()),
                null,
                operationId,
                liveDelta.isDirty(),
                null
        );
    }

    /**
     * Rebuilds Nether portal POIs after snapshot restoration.
     *
     * <p>Portal blocks are exact chunk content, but POI storage is auxiliary
     * metadata. Re-adding the POIs here keeps portal search behavior aligned with
     * the restored block grid.</p>
     *
     * @param world server world context
     * @param chunk target world chunk reference
     */
    @Unique
    private void chunkis$resyncPortalPointOfInterestStorage(final ServerWorld world, final WorldChunk chunk) {
        final PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        final RegistryEntry<PointOfInterestType> portalType =
                world.getRegistryManager()
                        .getOrThrow(RegistryKeys.POINT_OF_INTEREST_TYPE)
                        .getOrThrow(PointOfInterestTypes.NETHER_PORTAL);

        final int blockCount = chunkis$addPortalPois(chunk, poiStorage, portalType);
        if (blockCount == 0) {
            return;
        }

        final long poiCount = poiStorage.getInChunk(
                        PORTAL_POI_PREDICATE, chunk.getPos(),
                        PointOfInterestStorage.OccupationStatus.ANY
                )
                .count();

        if (poiCount == 0) {
            Chunkis.LOGGER.warn(
                    "Chunkis [PORTAL]: Restored chunk {} in {} has {} portal block(s) but no portal POIs " +
                            "after resync",
                    chunk.getPos(),
                    world.getRegistryKey()
                            .getValue(),
                    blockCount
            );
        }
    }

    /**
     * Adds portal POIs for every restored Nether portal block in the chunk.
     *
     * @param chunk      target world chunk reference
     * @param poiStorage point of interest storage reference
     * @param portalType point of interest type entry
     * @return count of portal blocks matched
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
     * Resolves the active tracked delta or creates a new one claiming ownership.
     *
     * @param chunk           target world chunk
     * @param ownershipReason ownership claim reason code
     * @param source          class/method trace source trigger label
     * @return active block delta mapping updates
     */
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
                    chunk.getWorld()
                            .getRegistryKey(),
                    chunk.getPos(),
                    "CLAIMED",
                    ownershipReason,
                    source + "#createDelta",
                    delta,
                    chunkis$getSuppressionCause(chunk)
            );
        }
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            delta.claimOwnership(ownershipReason.name(), source);
            ChunkOwnershipTraceHelper.traceDecision(
                    chunk.getWorld()
                            .getRegistryKey(),
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
     *
     * @return self casted to WorldChunk
     */
    @Unique
    private WorldChunk chunkis$self() {
        return (WorldChunk) (Object) this;
    }
}
