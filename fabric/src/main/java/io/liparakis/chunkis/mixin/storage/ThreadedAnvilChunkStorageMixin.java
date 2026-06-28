package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureScheduler;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.restoration.capture.CisSnapshotCapture;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.entity.capture.LiveEntitySnapshotCapture;
import io.liparakis.chunkis.world.restoration.capture.SnapshotSafetyChecker;
import io.liparakis.chunkis.world.tracking.ownership.PendingVanillaSaveDecision;
import io.liparakis.chunkis.world.restoration.nbt.StructureMetadataExtractor;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Intercepts chunk load and save operations in {@link ServerChunkLoadingManager},
 * routing persistence through Chunkis' CIS delta storage instead of vanilla MCA files.
 *
 * <h3>Load path</h3>
 * <p>{@link #chunkis$onGetUpdatedChunkNbt} provides synthetic NBT built from the CIS
 * delta, which then flows through vanilla's normal deserialization where
 * {@code ChunkSerializerMixin} attaches the delta to the resulting
 * {@link net.minecraft.world.chunk.ProtoChunk}.</p>
 *
 * <h3>Save path</h3>
 * <p>{@link #chunkis$onSave} captures an authoritative compact chunk snapshot and
 * flushes it to CIS storage.</p>
 *
 * <h3>Shutdown path</h3>
 * <p>{@link #chunkis$onClose} force-saves dirty deltas still present in the global
 * tracker, then closes world storage.</p>
 *
 * <h3>Threading</h3>
 * <p>These hooks run on the server chunk I/O/save path. External synchronization
 * around Chunkis storage is owned by the storage-helper and tracker layers.</p>
 *
 * @author Liparakis
 * @version 1.4
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ThreadedAnvilChunkStorageMixin {
    @Unique
    private static final String SAVE_SOURCE = "ThreadedAnvilChunkStorageMixin#chunkis$onSave";
    @Unique
    private static final String REJECT_SOURCE = "ThreadedAnvilChunkStorageMixin#chunkis$rejectSparse";
    @Unique
    private static final String ENTITY_REPLAY_SOURCE =
            "ThreadedAnvilChunkStorageMixin#chunkis$scheduleEntityReplay";

    /**
     * The server world that owns this chunk loading manager.
     */
    @Shadow
    @Final
    ServerWorld world;

    @Unique
    private void chunkis$traceWatchedEntityTransfer(
            final Entity entity,
            final String stage,
            final String source,
            final String message
    ) {
        if (entity == null) {
            return;
        }
        PayloadWatchTracer.traceEntityChunkTransfer(
                world,
                entity,
                null,
                stage,
                source,
                message
        );
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void chunkis$replayPendingEntities(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }
        if (deltaDuck.chunkis$getRestoreOperationId() != null) {
            return;
        }
        if (!(deltaDuck.chunkis$getDelta() instanceof ChunkDelta<?, ?> rawDelta)) {
            return;
        }
        final ChunkDelta<BlockState, NbtCompound> delta =
                (ChunkDelta<BlockState, NbtCompound>) rawDelta;
        if (delta.countPendingEntities() == 0) {
            return;
        }
        io.liparakis.chunkis.world.restoration.core.ChunkRestorer.replayPendingEntitiesIfNeeded(
                world,
                chunk,
                delta,
                deltaDuck.chunkis$getRestoreOperationId()
        );
    }

    @Unique
    private void chunkis$scheduleEntityReplay(final Entity entity) {
        if (entity == null) {
            return;
        }
        ScheduledEntityReplayQueue.acknowledge(entity.getUuidAsString());
    }

    /**
     * Ensures CIS storage is flushed and closed during server shutdown.
     *
     * <p>Injected at {@code TAIL} so Minecraft's own final save pass has already
     * run while Chunkis storage is still open. The force-save sweep is a safety
     * net for dirty deltas that survived the final tick.</p>
     *
     * @param ci the Mixin {@link CallbackInfo}
     */
    @Inject(method = "close", at = @At("TAIL"))
    private void chunkis$onClose(final CallbackInfo ci) {
        BaseChunkCaptureScheduler.flushAndClose(world);

        final Map<ChunkPos, ChunkDelta<BlockState, NbtCompound>> pending =
                GlobalChunkTracker.getPendingDeltas(world);

        chunkis$forceSaveRemainingDeltas(chunkis$getStorage(), pending);
        AsyncCisSaveManager.flushAndClose(world);
        FabricCisStorageHelper.closeStorage(world);
    }

    @Inject(method = "loadEntity", at = @At("HEAD"))
    private void chunkis$traceWatchedEntityLoad(final Entity entity, final CallbackInfo ci) {
        chunkis$traceWatchedEntityTransfer(
                entity,
                "entity-manager-load",
                "ThreadedAnvilChunkStorageMixin#chunkis$traceWatchedEntityLoad",
                "watched entity entered ServerChunkLoadingManager.loadEntity"
        );
    }

    @Inject(method = "unloadEntity", at = @At("HEAD"))
    private void chunkis$traceWatchedEntityUnload(final Entity entity, final CallbackInfo ci) {
        chunkis$traceWatchedEntityTransfer(
                entity,
                "entity-manager-unload",
                "ThreadedAnvilChunkStorageMixin#chunkis$traceWatchedEntityUnload",
                "watched entity entered ServerChunkLoadingManager.unloadEntity"
        );
        chunkis$scheduleEntityReplay(entity);
    }

    @Inject(method = "makeChunkEntitiesTickable", at = @At("RETURN"))
    private void chunkis$traceChunkEntitiesTickable(
            final ChunkHolder holder,
            final CallbackInfoReturnable<CompletableFuture<net.minecraft.server.world.OptionalChunk<WorldChunk>>> cir
    ) {
        cir.getReturnValue().thenAccept(optionalChunk ->
                optionalChunk.ifPresent(chunk -> {
                    chunkis$replayPendingEntities(chunk);
                    PayloadWatchTracer.traceEntityChunkReentry(
                            world,
                            chunk,
                            "chunk-entities-tickable",
                            "ThreadedAnvilChunkStorageMixin#chunkis$traceChunkEntitiesTickable",
                            "chunk became entity-tickable"
                    );
                }));
    }

    @Inject(method = "makeChunkTickable", at = @At("RETURN"))
    private void chunkis$traceChunkTickable(
            final ChunkHolder holder,
            final CallbackInfoReturnable<CompletableFuture<net.minecraft.server.world.OptionalChunk<WorldChunk>>> cir
    ) {
        cir.getReturnValue().thenAccept(optionalChunk ->
                optionalChunk.ifPresent(chunk -> {
                    chunkis$replayPendingEntities(chunk);
                    PayloadWatchTracer.traceEntityChunkReentry(
                            world,
                            chunk,
                            "chunk-tickable",
                            "ThreadedAnvilChunkStorageMixin#chunkis$traceChunkTickable",
                            "chunk became tickable"
                    );
                }));
    }

    /**
     * Provides CIS-backed NBT for chunk loading and cancels vanilla MCA loading.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>In-memory tracker delta (flushed if dirty before NBT is built).</li>
     *   <li>CIS disk storage (cold load).</li>
     * </ol>
     *
     * <p>The returned NBT is intentionally minimal unless a persisted base chunk
     * exists. Its job is to carry Chunkis metadata and delta payloads through
     * vanilla deserialization, not to replicate the full MCA format.</p>
     *
     * @param chunkPos the chunk position being loaded
     * @param cir      the callback whose return value is replaced with synthetic NBT
     */
    @Inject(
            method = "getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$onGetUpdatedChunkNbt(
            final ChunkPos chunkPos,
            final CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = chunkis$getStorage();

        ChunkDelta<BlockState, NbtCompound> delta = chunkis$getTrackedDelta(chunkPos);

        if (chunkis$shouldBypassTrackedDelta(delta)) {
            final CisChunkPos cisPos = new CisChunkPos(chunkPos.x, chunkPos.z);
            if (!storage.contains(cisPos)) {
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.CHUNK_LIFECYCLE,
                        ChunkTraceEventType.LOAD_SOURCE_RESOLVED,
                        ChunkTraceSeverity.INFO,
                        ChunkTraceReason.NEITHER,
                        "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                        "load source resolved: no tracker delta and no storage entry",
                        world.getRegistryKey().getValue().toString(),
                        new DebugChunkKey(chunkPos.x, chunkPos.z),
                        null,
                        null,
                        false,
                        null
                );
                ChunkOwnershipTraceHelper.traceDecision(
                        world.getRegistryKey(),
                        chunkPos,
                        "BYPASSED",
                        ChunkTraceReason.PASSIVE_VANILLA_LOAD,
                        "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                        null,
                        ChunkMutationTrackingScope.Cause.PASSIVE_LOAD
                );
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.CHUNK_LIFECYCLE,
                        ChunkTraceEventType.LOAD_TX_END,
                        ChunkTraceSeverity.INFO,
                        ChunkTraceReason.NEITHER,
                        "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                        "bypassed synthetic load because tracker and storage were empty",
                        world.getRegistryKey().getValue().toString(),
                        new DebugChunkKey(chunkPos.x, chunkPos.z),
                        null,
                        null,
                        false,
                        null
                );
                return;
            }
            delta = storage.load(cisPos);
        } else {
            chunkis$saveDirtyDelta(storage, chunkPos, delta);
        }

        if (!ChunkDeltaOwnership.hasRestorableChunkisState(delta)) {
            final ChunkTraceReason resolvedReason = chunkis$shouldBypassTrackedDelta(delta)
                    ? ChunkTraceReason.NEITHER
                    : ChunkTraceReason.TRACKER_MEMORY;
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.LOAD_SOURCE_RESOLVED,
                    ChunkTraceSeverity.INFO,
                    resolvedReason,
                    "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                    "load source resolved but delta had no restorable Chunkis state",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    null,
                    delta != null && delta.isDirty(),
                    null
            );
            ChunkOwnershipTraceHelper.traceDecision(
                    world.getRegistryKey(),
                    chunkPos,
                    "BYPASSED",
                    ChunkTraceReason.PASSIVE_VANILLA_LOAD,
                    "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                    delta,
                    ChunkMutationTrackingScope.Cause.PASSIVE_LOAD
            );
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.LOAD_TX_END,
                    ChunkTraceSeverity.INFO,
                    resolvedReason,
                    "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                    "bypassed synthetic load because delta had no restorable state",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    null,
                    delta != null && delta.isDirty(),
                    null
            );
            return;
        }
        ChunkOwnershipTraceHelper.claimOwnership(
                delta,
                ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE,
                "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt"
        );
        ChunkOwnershipTraceHelper.traceDecision(
                world.getRegistryKey(),
                chunkPos,
                "CLAIMED",
                ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE,
                "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                delta,
                ChunkMutationTrackingScope.Cause.PASSIVE_LOAD
        );

        final boolean hasPersistedBaseChunk =
                delta != null && CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata());
        final boolean authoritativeFullBaseline =
                delta != null && CisNbtUtil.hasFullBlockBaseline(delta.getChunkMetadata());
        if (hasPersistedBaseChunk) {
            final NbtCompound baseChunkNbt = CisNbtUtil.extractPersistedBaseChunkNbt(delta.getChunkMetadata());
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.BASE_NBT_DECODE_STARTED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                    "base NBT decode started: loadSource="
                            + (delta != null ? "delta-present" : "none")
                            + ", storageEntryExists="
                            + (delta != null && !delta.isEmpty())
                            + ", metadataKeys="
                            + (delta != null && delta.getChunkMetadata() != null
                            ? delta.getChunkMetadata().getKeys()
                            : List.of())
                            + ", baseNbtKeys="
                            + (baseChunkNbt != null ? baseChunkNbt.getKeys() : List.of())
                            + ", baseNbtApproxBytes="
                            + (baseChunkNbt != null ? baseChunkNbt.toString().length() : 0),
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    null,
                    delta != null && delta.isDirty(),
                    null
            );
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                hasPersistedBaseChunk
                        ? ChunkTraceEventType.BASE_NBT_FOUND
                        : ChunkTraceEventType.BASE_NBT_MISSING,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                hasPersistedBaseChunk
                        ? "found persisted base chunk NBT in delta metadata"
                        : "no persisted base chunk NBT in delta metadata",
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                null,
                delta != null && delta.isDirty(),
                null
        );

        final CisNbtUtil.LoadChunkNbtResult loadNbt =
                CisNbtUtil.buildLoadChunkNbt(chunkPos, chunkis$getGameDataVersion(), delta);
        if (authoritativeFullBaseline
                && loadNbt.baseChunkUsage() == CisNbtUtil.PersistedBaseChunkUsage.USED) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                    "authoritative v11 full-baseline delta attempted to use persisted base chunk NBT as block baseline",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    null,
                    delta != null && delta.isDirty(),
                    null
            );
        }
        if (loadNbt.baseChunkUsage() == CisNbtUtil.PersistedBaseChunkUsage.USED) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.BASE_NBT_APPLIED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                    "applied persisted base chunk NBT to synthetic load root",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    null,
                    delta != null && delta.isDirty(),
                    null
            );
        } else if (loadNbt.baseChunkUsage() == CisNbtUtil.PersistedBaseChunkUsage.SKIPPED) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.BASE_NBT_SKIPPED,
                    ChunkTraceSeverity.WARN,
                    ChunkTraceReason.NONE,
                    "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                    authoritativeFullBaseline
                            ? "persisted base chunk NBT was present but skipped because authoritative v11 payload " +
                              "owns blocks"
                            : "persisted base chunk NBT was present but skipped for load root",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    null,
                    delta != null && delta.isDirty(),
                    null
            );
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "ThreadedAnvilChunkStorageMixin#chunkis$onGetUpdatedChunkNbt",
                "built load NBT with baseChunkNbt="
                        + loadNbt.baseChunkUsage().name().toLowerCase(Locale.ROOT),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                null,
                delta != null && delta.isDirty(),
                null
        );
        cir.setReturnValue(CompletableFuture.completedFuture(
                Optional.of(loadNbt.root())));
    }

    /**
     * Captures Chunkis metadata and saves dirty deltas through CIS storage,
     * suppressing vanilla MCA writes entirely.
     *
     * <p>Delta resolution order:</p>
     * <ol>
     *   <li>Global tracker (reflects in-flight modifications).</li>
     *   <li>Chunk duck interface (fallback for chunks absent from the tracker).</li>
     * </ol>
     *
     * <p>Always returns {@code true} to suppress vanilla MCA writes. If no
     * Chunkis delta exists and no metadata needs capturing, the save is a no-op
     * from Chunkis' perspective.</p>
     *
     * @param chunkHolder the holder containing the chunk to save
     * @param currentTime the vanilla save timestamp (unused by Chunkis)
     * @param cir         the callback whose return value is forced to {@code true}
     */
    @Inject(
            method = "save(Lnet/minecraft/server/world/ChunkHolder;J)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$onSave(
            final ChunkHolder chunkHolder,
            final long currentTime,
            final CallbackInfoReturnable<Boolean> cir) {
        final ChunkPos pos = chunkHolder.getPos();
        final String operationId = ChunkTraceStore.nextOperationId("save");
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SAVE_SOURCE,
                "save hook requested",
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                operationId,
                null,
                null
        );
        final Chunk chunk = chunkis$resolveChunkForSaving(chunkHolder);
        ChunkDelta<BlockState, NbtCompound> delta = chunkis$resolveDeltaForSave(chunk, pos);
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            chunkis$finishBypassedSave(pos, delta, chunk, operationId, cir);
            return;
        }
        ChunkOwnershipTraceHelper.traceDecision(
                world.getRegistryKey(),
                pos,
                "CLAIMED",
                ChunkTraceReason.valueOf(delta.getOwnershipReason()),
                SAVE_SOURCE,
                delta,
                null
        );
        PayloadWatchTracer.traceDeltaStage(
                world.getRegistryKey().getValue().toString(),
                pos,
                delta,
                operationId,
                ChunkTraceEventType.WATCH_CAPTURED,
                "save-start",
                SAVE_SOURCE,
                "delta entered save transaction",
                null
        );

        delta = chunkis$captureSnapshot(chunk, delta, operationId);
        delta = chunkis$captureStructureMetadata(chunk, delta);
        delta = chunkis$captureLiveEntities(chunk, delta, operationId);

        if (delta != null && !delta.isDirty()) {
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey().getValue().toString(),
                    pos,
                    delta,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "save-clean-after-capture",
                    SAVE_SOURCE,
                    "delta was clean after snapshot/entity capture; async save not queued",
                    null
            );
        }

        if (delta != null) {
            if (chunk instanceof ChunkisDeltaDuck duck) {
                duck.chunkis$setDelta(delta);
            }
            chunkis$queueDirtyDelta(chunkis$getStorage(), pos, delta, operationId);
        }

        if (chunk != null) {
            chunk.tryMarkSaved();
        }

        cir.setReturnValue(Boolean.TRUE);
    }

    /**
     * Resolves the delta the save hook should operate on.
     *
     * <p>The active tracked delta wins. If none exists, the chunk-attached delta is
     * used as a fallback. As a last recovery step, a chunk with live savable
     * entities but no Chunkis ownership is promoted into an owned delta by
     * capturing those entities.</p>
     *
     * @param chunk chunk being saved; may be {@code null}
     * @param pos   chunk position for tracker lookup
     * @return delta to use for save processing, or {@code null}
     */
    @Unique
    private ChunkDelta<BlockState, NbtCompound> chunkis$resolveDeltaForSave(
            final Chunk chunk,
            final ChunkPos pos
    ) {
        ChunkDelta<BlockState, NbtCompound> delta = chunkis$getActiveDelta(pos);
        if (delta == null) {
            delta = chunkis$getChunkDelta(chunk);
        }
        if (ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            return delta;
        }
        if (!(chunk instanceof WorldChunk worldChunk)) {
            return delta;
        }
        if (LiveEntitySnapshotCapture.countSavableLiveEntities(world, worldChunk) == 0) {
            return delta;
        }

        delta = chunkis$captureLiveEntities(chunk, delta, null);
        ChunkOwnershipTraceHelper.claimOwnership(
                delta,
                ChunkTraceReason.EXPLICIT_CHUNKIS_MUTATION,
                SAVE_SOURCE + "#entityCapture"
        );
        return delta;
    }

    /**
     * Records the reason a save stayed on the vanilla-bypassed path and stops the
     * hook without queueing a Chunkis save.
     *
     * @param pos         chunk position being saved
     * @param delta       resolved delta, if any
     * @param chunk       resolved chunk, if any
     * @param operationId trace correlation ID
     * @param cir         callback whose return value is forced to {@code true}
     */
    @Unique
    private void chunkis$finishBypassedSave(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final Chunk chunk,
            final String operationId,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        final int liveSavableEntities = chunk instanceof WorldChunk worldChunk
                ? LiveEntitySnapshotCapture.countSavableLiveEntities(world, worldChunk)
                : 0;
        if (delta != null && delta.isDirty()) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.SAVE_WITHOUT_OWNERSHIP,
                    SAVE_SOURCE,
                    "save hook saw dirty delta without ownership",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(pos.x, pos.z),
                    null,
                    operationId,
                    true,
                    null
            );
        }
        if (liveSavableEntities > 0) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.SAVE_WITHOUT_OWNERSHIP,
                    SAVE_SOURCE,
                    "save hook bypassed chunk with " + liveSavableEntities
                            + " savable live entit"
                            + (liveSavableEntities == 1 ? "y" : "ies")
                            + " before Chunkis entity capture",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(pos.x, pos.z),
                    null,
                    operationId,
                    delta != null && delta.isDirty(),
                    null
            );
        }
        ChunkOwnershipTraceHelper.traceDecision(
                world.getRegistryKey(),
                pos,
                "BYPASSED",
                ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED,
                SAVE_SOURCE,
                delta,
                null
        );
        PendingVanillaSaveDecision.put(pos, delta, ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED, SAVE_SOURCE);
        cir.setReturnValue(Boolean.TRUE);
    }

    /**
     * Returns the delta currently tracked for {@code pos} (memory + unload cache),
     * or {@code null}.  Used by the load path to preserve pending in-memory state
     * before building synthetic load NBT.
     *
     * @param pos the chunk position
     * @return the tracked delta, or {@code null}
     */
    @Unique
    @SuppressWarnings("unchecked")
    private ChunkDelta<BlockState, NbtCompound> chunkis$getTrackedDelta(final ChunkPos pos) {
        return (ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker.getDelta(world, pos);
    }

    /**
     * Returns the active (dirty-map only) delta for {@code pos}, or {@code null}.
     * Used by the save path, which prefers active deltas over the duck fallback.
     *
     * @param pos the chunk position
     * @return the active dirty delta, or {@code null}
     */
    @Unique
    @SuppressWarnings("unchecked")
    private ChunkDelta<BlockState, NbtCompound> chunkis$getActiveDelta(final ChunkPos pos) {
        return (ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker.getActiveDelta(world, pos);
    }

    /**
     * Returns the delta attached to {@code chunk} via {@link ChunkisDeltaDuck},
     * or {@code null} if the chunk is {@code null} or does not implement the
     * interface.  Fallback for chunks absent from the global tracker.
     *
     * @param chunk the chunk to inspect; may be {@code null}
     * @return the attached delta, or {@code null}
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> chunkis$getChunkDelta(final Chunk chunk) {
        return chunk instanceof ChunkisDeltaDuck duck
                ? (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta()
                : null;
    }

    /**
     * Resolves the best available chunk instance for saving.
     *
     * <p>The live world chunk is preferred; if absent, the saving future is
     * checked without blocking.</p>
     *
     * @param holder the chunk holder
     * @return the resolved chunk, or {@code null}
     */
    @Unique
    private static Chunk chunkis$resolveChunkForSaving(final ChunkHolder holder) {
        final Chunk live = holder.getWorldChunk();
        if (live != null) {
            return live;
        }
        final Object result = holder.getSavingFuture().getNow(null);
        if (!(result instanceof Optional<?> opt)) {
            return null;
        }
        final Object value = opt.orElse(null);
        return value instanceof Chunk c ? c : null;
    }

    /**
     * Captures serialized structure metadata into the chunk delta.
     *
     * <p>Structure starts and references must survive Chunkis' regenerate-on-load
     * behavior. Without this metadata vanilla can treat structures as missing or
     * eligible for repopulation after reload.</p>
     *
     * <p>Returns {@code null} when {@code chunk} is {@code null} and
     * {@code existingDelta} is {@code null}, to avoid allocating a delta for
     * nothing.</p>
     *
     * @param chunk         the chunk being saved; may be {@code null}
     * @param existingDelta the existing delta; may be {@code null}
     * @return the updated delta, the original delta, or {@code null}
     */
    @Unique
    private ChunkDelta<BlockState, NbtCompound> chunkis$captureStructureMetadata(
            final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> existingDelta) {
        if (chunk == null) {
            return existingDelta;
        }

        final NbtCompound structureData = chunkis$extractStructureMetadata(chunk);
        final boolean hasStructures = CisNbtUtil.hasStructureData(structureData);

        if (existingDelta == null && !hasStructures) {
            return null;
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                existingDelta != null ? existingDelta : new ChunkDelta<>();
        final NbtCompound existingMetadata = delta.getChunkMetadata();

        delta.setSuppressInitialRepopulation(true);

        if (!hasStructures) {
            return delta;
        }

        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                structureData,
                true,
                CisNbtUtil.hasFullBlockBaseline(existingMetadata),
                CisNbtUtil.extractPersistedBaseChunkNbt(existingMetadata),
                chunk instanceof WorldChunk worldChunk
                        ? io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil.hasPortalBlocks(worldChunk)
                        : CisNbtUtil.hasPersistedPortalChunk(existingMetadata)
        );

        chunkis$updateDeltaMetadata(delta, metadata, chunk.getPos());
        return delta;
    }

    /**
     * Captures an authoritative CIS snapshot from the live chunk.
     */
    @Unique
    private ChunkDelta<BlockState, NbtCompound> chunkis$captureSnapshot(
            final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> existingDelta,
            final String operationId) {
        if (!(chunk instanceof WorldChunk worldChunk)
                || !ChunkStatus.FULL.equals(chunk.getStatus())) {
            return existingDelta;
        }
        if (SnapshotSafetyChecker.isSnapshotUnsafe(worldChunk)) {
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey().getValue().toString(),
                    worldChunk.getPos(),
                    existingDelta,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "save-snapshot-unsafe",
                    SAVE_SOURCE,
                    "skipped full snapshot capture because chunk was not snapshot-safe",
                    null
            );
            return existingDelta;
        }

        final ChunkDelta<BlockState, NbtCompound> delta = existingDelta != null
                ? existingDelta
                : new ChunkDelta<>(BlockState::isAir);
        return CisSnapshotCapture.capture(worldChunk, delta, operationId);
    }

    /**
     * Captures the current non-player entity set for the chunk before persistence.
     *
     * <p>Vanilla entity storage is disabled, so Chunkis must own these payloads
     * completely rather than relying on legacy replay leftovers.</p>
     *
     * @param chunk         the chunk being saved; may be {@code null}
     * @param existingDelta the existing delta; may be {@code null}
     * @return the updated delta, the original delta, or {@code null}
     */
    @Unique
    private ChunkDelta<BlockState, NbtCompound> chunkis$captureLiveEntities(
            final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> existingDelta,
            final String operationId) {
        if (!(chunk instanceof WorldChunk worldChunk)) {
            return existingDelta;
        }
        return LiveEntitySnapshotCapture.capture(
                world,
                worldChunk,
                existingDelta,
                operationId,
                "ThreadedAnvilChunkStorageMixin#chunkis$captureLiveEntities"
        );
    }

    /**
     * Saves {@code delta} synchronously if it is dirty and passes the persistence
     * guard.
     *
     * <p>The {@link CisChunkPos} is allocated only after the dirty check so clean
     * deltas incur no allocation.</p>
     *
     * @param storage the storage to save into
     * @param pos     the chunk position
     * @param delta   the delta to save; may be {@code null}
     */
    @Unique
    private void chunkis$saveDirtyDelta(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final String operationId = ChunkTraceStore.nextOperationId("save");
        ChunkDelta<BlockState, NbtCompound> deltaToSave = delta;
        if (deltaToSave == null || !deltaToSave.isDirty()) {
            return;
        }
        deltaToSave = chunkis$recoverSparseDeltaOnSaveGuard(
                pos,
                deltaToSave,
                "load-path-sync",
                "ThreadedAnvilChunkStorageMixin#chunkis$saveDirtyDelta",
                operationId
        );
        if (chunkis$rejectSparse(
                pos, deltaToSave, "load-path-sync", "ThreadedAnvilChunkStorageMixin#chunkis$saveDirtyDelta"
                , operationId
        )) {
            return;
        }

        if (storage.save(new CisChunkPos(pos.x, pos.z), deltaToSave, operationId)) {
            GlobalChunkTracker.markSaved(world, pos);
        }
    }

    /**
     * Queues {@code delta} for async compression and disk write if it is dirty and
     * passes the persistence guard.
     *
     * <p>Only the normal save hook uses this path. Load-path and shutdown safety
     * flushes remain synchronous.</p>
     *
     * @param storage the storage to save into
     * @param pos     the chunk position
     * @param delta   the delta to queue; may be {@code null}
     */
    @Unique
    private void chunkis$queueDirtyDelta(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId) {
        ChunkDelta<BlockState, NbtCompound> deltaToQueue = delta;
        if (deltaToQueue == null || !deltaToQueue.isDirty()) {
            if (deltaToQueue != null) {
                PayloadWatchTracer.traceDeltaStage(
                        world.getRegistryKey().getValue().toString(),
                        pos,
                        deltaToQueue,
                        operationId,
                        ChunkTraceEventType.WATCH_SKIPPED,
                        "save-not-queued-clean",
                        "ThreadedAnvilChunkStorageMixin#chunkis$queueDirtyDelta",
                        "delta was clean when queueing was attempted; async save not queued",
                        null
                );
            }
            return;
        }
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(deltaToQueue)) {
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey().getValue().toString(),
                    pos,
                    deltaToQueue,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "save-bypassed-without-ownership",
                    "ThreadedAnvilChunkStorageMixin#chunkis$queueDirtyDelta",
                    "chunk was not Chunkis-owned; async save not queued",
                    null
            );
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.SAVE_WITHOUT_OWNERSHIP,
                    "ThreadedAnvilChunkStorageMixin#chunkis$queueDirtyDelta",
                    "attempted to queue Chunkis save without ownership",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(pos.x, pos.z),
                    null,
                    operationId,
                    true,
                    null
            );
            ChunkOwnershipTraceHelper.traceDecision(
                    world.getRegistryKey(),
                    pos,
                    "BYPASSED",
                    ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED,
                    "ThreadedAnvilChunkStorageMixin#chunkis$queueDirtyDelta",
                    deltaToQueue,
                    null
            );
            return;
        }
        ChunkOwnershipTraceHelper.traceDecision(
                world.getRegistryKey(),
                pos,
                "CLAIMED",
                ChunkTraceReason.valueOf(deltaToQueue.getOwnershipReason()),
                "ThreadedAnvilChunkStorageMixin#chunkis$queueDirtyDelta",
                deltaToQueue,
                null
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_QUEUE_REQUESTED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "ThreadedAnvilChunkStorageMixin#chunkis$queueDirtyDelta",
                "save queue requested: " + DeltaPersistenceGuard.describeLifecycleState(delta),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                operationId,
                deltaToQueue.isDirty(),
                null
        );
        deltaToQueue = chunkis$recoverSparseDeltaOnSaveGuard(
                pos,
                deltaToQueue,
                "save-hook-async",
                "ThreadedAnvilChunkStorageMixin#chunkis$queueDirtyDelta",
                operationId
        );
        if (chunkis$rejectSparse(
                pos, deltaToQueue, "save-hook-async", "ThreadedAnvilChunkStorageMixin" +
                        "#chunkis$queueDirtyDelta", operationId
        )) {
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey().getValue().toString(),
                    pos,
                    deltaToQueue,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "save-rejected-sparse",
                    "ThreadedAnvilChunkStorageMixin#chunkis$queueDirtyDelta",
                    "delta was rejected by sparse persistence guard; async save not queued",
                    null
            );
            return;
        }

        AsyncCisSaveManager.submit(world, storage, pos, deltaToQueue, operationId);
    }

    @Unique
    private ChunkDelta<BlockState, NbtCompound> chunkis$recoverSparseDeltaOnSaveGuard(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String path,
            final String caller,
            final String operationId
    ) {
        chunkis$traceBaseMetadataBeforeSaveGuard(pos, delta, caller, operationId);
        if (!DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta, true)) {
            return delta;
        }

        final WorldChunk liveChunk = world.getChunkManager().getWorldChunk(pos.x, pos.z, false);
        final ChunkDelta<BlockState, NbtCompound> liveDelta = liveChunk != null
                ? chunkis$getChunkDelta(liveChunk)
                : null;

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.BASE_CAPTURE_ON_SAVE_GUARD,
                liveChunk != null ? ChunkTraceSeverity.INFO : ChunkTraceSeverity.WARN,
                ChunkTraceReason.NONE,
                caller,
                liveChunk != null
                        ? "capturing base snapshot on save guard before retry: "
                          + DeltaPersistenceGuard.describeLifecycleState(liveDelta != null ? liveDelta : delta)
                        : "could not capture base snapshot on save guard because live chunk was unavailable: "
                          + DeltaPersistenceGuard.describeLifecycleState(delta),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                operationId,
                delta.isDirty(),
                null
        );

        if (liveChunk == null) {
            return delta;
        }

        final ChunkDelta<BlockState, NbtCompound> recoveredDelta = liveDelta != null ? liveDelta : delta;
        BaseChunkCaptureUtil.captureBaseChunk(world, liveChunk, recoveredDelta);
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_RETRY_AFTER_BASE_CAPTURE,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                caller,
                "retrying save after base capture: " + DeltaPersistenceGuard.describeLifecycleState(recoveredDelta),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                operationId,
                recoveredDelta.isDirty(),
                null
        );
        return recoveredDelta;
    }

    @Unique
    private void chunkis$traceBaseMetadataBeforeSaveGuard(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String caller,
            final String operationId
    ) {
        final boolean hasBase = CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata());
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                hasBase
                        ? ChunkTraceEventType.BASE_METADATA_PRESENT_BEFORE_SAVE_GUARD
                        : ChunkTraceEventType.BASE_METADATA_MISSING_BEFORE_SAVE_GUARD,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                caller,
                "save guard precheck: " + DeltaPersistenceGuard.describeLifecycleState(delta),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                operationId,
                delta.isDirty(),
                null
        );
    }

    /**
     * Checks the sparse-delta guard and logs a rejection if triggered.
     *
     * <p>Extracted to eliminate the identical guard + log pair in both
     * {@link #chunkis$saveDirtyDelta} and {@link #chunkis$queueDirtyDelta}.</p>
     *
     * @param pos    the chunk position
     * @param delta  the delta under evaluation
     * @param path   save path label
     * @param caller caller label
     * @return {@code true} if the save should be rejected
     */
    @Unique
    private boolean chunkis$rejectSparse(
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta,
            final String path,
            final String caller,
            final String operationId) {
        if (DeltaPersistenceGuard.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta)) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    caller,
                    "attempted to persist sparse block-entity payload without persisted base chunk NBT on "
                            + path + ": " + DeltaPersistenceGuard.describeDeltaShape(delta),
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(pos.x, pos.z),
                    null,
                    operationId,
                    delta.isDirty(),
                    null
            );
        }
        if (!DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta)) {
            return false;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.SAVE_GUARDS,
                ChunkTraceEventType.SAVE_REJECTED,
                ChunkTraceSeverity.WARN,
                ChunkTraceReason.SPARSE_DELTA_REJECTED,
                REJECT_SOURCE,
                "rejected sparse delta on " + path,
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                operationId,
                delta.isDirty(),
                null
        );
        DeltaPersistenceGuard.logRejectedSparseDeltaWithoutBase(
                world,
                pos,
                delta,
                path,
                caller
        );
        return true;
    }

    /**
     * Force-saves all dirty deltas still present in the global tracker.
     *
     * <p>Used during shutdown as a last safety sweep. Logs when work remains so
     * operators can see how many deltas Chunkis had to flush at close time.</p>
     *
     * @param storage the storage to save into
     * @param pending the pending dirty delta map
     */
    @Unique
    private void chunkis$forceSaveRemainingDeltas(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final Map<ChunkPos, ChunkDelta<BlockState, NbtCompound>> pending) {
        if (pending.isEmpty()) {
            return;
        }
        Chunkis.LOGGER.warn(
                "Chunkis [CLOSE]: Force-saving {} remaining dirty delta(s) for {}",
                pending.size(),
                world.getRegistryKey().getValue()
        );
        for (final Map.Entry<ChunkPos, ChunkDelta<BlockState, NbtCompound>> entry : pending.entrySet()) {
            chunkis$saveDirtyDelta(storage, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Builds the minimal NBT compound used to ferry Chunkis data through vanilla
     * chunk deserialization.
     *
     * <p>Delegates to {@link CisNbtUtil#buildLoadChunkNbt} so persisted base chunk
     * NBT can become the vanilla deserialization baseline when present. Without
     * that baseline, Chunkis falls back to the synthetic empty-shell NBT that
     * triggers regeneration before sparse replay.</p>
     *
     * @param pos   the chunk position
     * @param delta the delta to embed; may be {@code null}
     * @return a populated chunk NBT compound
     */
    @Unique
    private static NbtCompound chunkis$buildChunkNbt(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        return CisNbtUtil.buildLoadChunkNbt(pos, chunkis$getGameDataVersion(), delta).root();
    }

    @Unique
    private static int chunkis$getGameDataVersion() {
        return SharedConstants.getGameVersion().dataVersion().id();
    }

    /**
     * Extracts structure metadata from a live chunk.
     *
     * <p>The direct extractor is preferred to avoid full vanilla serialization. If
     * it fails, {@link SerializedChunk} is used as a safe fallback.</p>
     *
     * @param chunk the chunk to inspect
     * @return the structure metadata compound
     */
    @Unique
    private NbtCompound chunkis$extractStructureMetadata(final Chunk chunk) {
        try {
            return StructureMetadataExtractor.extract(world, chunk);
        } catch (final Exception e) {
            Chunkis.LOGGER.warn(
                    "Chunkis: Direct structure metadata extraction failed for chunk {}, " +
                            "falling back to SerializedChunk",
                    chunk.getPos(), e
            );
            return SerializedChunk.fromChunk(world, chunk).structureData();
        }
    }

    /**
     * Writes {@code metadata} into {@code delta}, preferring the pre-encoded path
     * for performance.  Falls back to a direct set if encoding fails so structure
     * metadata is never silently lost.
     *
     * @param delta    the delta to update
     * @param metadata the metadata compound to install
     * @param pos      the chunk position used for the fallback warning log
     */
    @Unique
    private static void chunkis$updateDeltaMetadata(
            final ChunkDelta<BlockState, NbtCompound> delta,
            final NbtCompound metadata,
            final ChunkPos pos) {
        try {
            delta.updateChunkMetadataWithEncodedPayload(
                    metadata, CisNbtUtil.serializeRawPayload(metadata));
        } catch (final Exception e) {
            Chunkis.LOGGER.warn(
                    "Chunkis: Failed to pre-encode structure metadata for chunk {}, " +
                            "falling back to direct metadata update",
                    pos, e
            );
            delta.setChunkMetadata(metadata);
        }
    }

    /**
     * Serializes a live entity through Minecraft's write-view path.
     *
     * <p>Failures are logged at DEBUG and return {@code null} so the save path
     * silently skips unserializable entities without aborting the whole capture.</p>
     *
     * @param entity the entity to serialize
     * @return the serialized NBT, or {@code null} if serialization failed
     */
    /**
     * Returns the CIS storage instance for {@link #world}.
     *
     * @return the CIS storage for the owning world
     */
    @Unique
    private CisStorage<Block, BlockState, Property<?>, NbtCompound> chunkis$getStorage() {
        return FabricCisStorageHelper.getStorage(world);
    }

    /**
     * Returns {@code true} when a tracked in-memory delta is too weak to be
     * authoritative for load reconstruction and storage should be consulted instead.
     *
     * <p>An empty clean delta with no persisted base chunk or full-block baseline
     * is not useful reconstruction state preferring it over storage would cause
     * Chunkis to emit synthetic {@code STATUS_EMPTY} NBT and reroll terrain.</p>
     *
     * @param delta the tracked delta candidate; may be {@code null}
     * @return {@code true} when storage should be consulted instead
     */
    @Unique
    private static boolean chunkis$shouldBypassTrackedDelta(
            final ChunkDelta<BlockState, NbtCompound> delta) {
        return delta == null || (!delta.isDirty() && delta.isEmpty());
    }
}


