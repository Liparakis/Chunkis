package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.storage.AsyncCisSaveManager;
import io.liparakis.chunkis.storage.BaseChunkCaptureScheduler;
import io.liparakis.chunkis.storage.CisNbtUtil;
import io.liparakis.chunkis.storage.CisSnapshotCapture;
import io.liparakis.chunkis.storage.DeltaPersistenceGuard;
import io.liparakis.chunkis.storage.FabricCisStorageHelper;
import io.liparakis.chunkis.storage.StructureMetadataExtractor;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.GlobalChunkTracker;
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
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
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

import java.util.*;
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

    /**
     * Stable comparator for sorting live entities by UUID before NBT capture.
     * Deterministic order prevents spurious delta diffs across save cycles.
     */
    @Unique
    private static final Comparator<Entity> ENTITY_UUID_COMPARATOR =
            Comparator.comparing(Entity::getUuid);

    /**
     * Game data version captured once at class-init time.
     *
     * <p>The version is process-stable, so reading it once avoids repeated
     * {@link SharedConstants} traversal when constructing synthetic chunk NBT.</p>
     */
    @Unique
    private static final int GAME_DATA_VERSION =
            SharedConstants.getGameVersion().dataVersion().id();

    /**
     * The server world that owns this chunk loading manager.
     */
    @Shadow
    @Final
    ServerWorld world;

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
            delta = storage.load(new CisChunkPos(chunkPos.x, chunkPos.z));
        } else {
            chunkis$saveDirtyDelta(storage, chunkPos, delta);
        }


        cir.setReturnValue(CompletableFuture.completedFuture(
                Optional.of(chunkis$buildChunkNbt(chunkPos, delta))));
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
                null,
                null,
                null
        );
        final Chunk chunk = chunkis$resolveChunkForSaving(chunkHolder);

        ChunkDelta<BlockState, NbtCompound> delta = chunkis$getActiveDelta(pos);
        if (delta == null) {
            delta = chunkis$getChunkDelta(chunk);
        }

        delta = chunkis$captureSnapshot(chunk, delta);
        delta = chunkis$captureStructureMetadata(chunk, delta);
        delta = chunkis$captureLiveEntities(chunk, delta);


        if (delta != null) {
            if (chunk instanceof ChunkisDeltaDuck duck) {
                duck.chunkis$setDelta(delta);
            }
            chunkis$queueDirtyDelta(chunkis$getStorage(), pos, delta);
        }

        if (chunk != null) {
            chunk.tryMarkSaved();
        }

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

        delta.setSuppressInitialRepopulation(true);

        if (!hasStructures) {
            return delta;
        }

        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                structureData,
                true,
                false,
                null,
                chunk instanceof WorldChunk worldChunk && io.liparakis.chunkis.storage.BaseChunkCaptureUtil.hasPortalBlocks(worldChunk)
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
            final ChunkDelta<BlockState, NbtCompound> existingDelta) {
        if (!(chunk instanceof WorldChunk worldChunk)
                || !ChunkStatus.FULL.equals(chunk.getStatus())) {
            return existingDelta;
        }

        final ChunkDelta<BlockState, NbtCompound> delta = existingDelta != null
                ? existingDelta
                : new ChunkDelta<>(BlockState::isAir);
        return CisSnapshotCapture.capture(worldChunk, delta);
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
            final ChunkDelta<BlockState, NbtCompound> existingDelta) {
        if (!(chunk instanceof WorldChunk worldChunk)) {
            return existingDelta;
        }

        final ChunkPos chunkPos = worldChunk.getPos();
        final Box bounds = new Box(
                chunkPos.getStartX(), world.getBottomY(), chunkPos.getStartZ(),
                chunkPos.getEndX() + 1, world.getBottomY() + world.getHeight(), chunkPos.getEndZ() + 1
        );

        final List<Entity> live = new ArrayList<>(world.getOtherEntities(null, bounds));
        live.sort(ENTITY_UUID_COMPARATOR);

        final List<NbtCompound> entities = new ArrayList<>();
        for (final Entity entity : live) {
            if (entity instanceof PlayerEntity || !entity.getChunkPos().equals(chunkPos)) {
                continue;
            }
            final NbtCompound nbt = chunkis$serializeEntityNbt(entity);
            if (nbt != null && !nbt.isEmpty()) {
                entities.add(nbt);
            }
        }

        final ChunkDelta<BlockState, NbtCompound> delta =
                existingDelta != null ? existingDelta : new ChunkDelta<>();
        delta.setEntities(entities, true);
        return delta;
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
        if (delta == null || !delta.isDirty()) {
            return;
        }
        if (chunkis$rejectSparse(pos, delta, "load-path-sync", "ThreadedAnvilChunkStorageMixin#chunkis$saveDirtyDelta"
        )) {
            return;
        }

        if (storage.save(new CisChunkPos(pos.x, pos.z), delta)) {
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
            final ChunkDelta<BlockState, NbtCompound> delta) {
        if (delta == null || !delta.isDirty()) {
            return;
        }
        if (chunkis$rejectSparse(
                pos, delta, "save-hook-async", "ThreadedAnvilChunkStorageMixin" +
                        "#chunkis$queueDirtyDelta"
        )) {
            return;
        }

        AsyncCisSaveManager.submit(world, storage, pos, delta);
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
            final String caller) {
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
                null,
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
     * <p>If the delta contains a persisted vanilla-compatible base chunk, that base
     * is reused and the delta payloads are merged into it. Otherwise a small
     * synthetic NBT compound is created with the chunk position, data version,
     * metadata, and delta payload.</p>
     *
     * @param pos   the chunk position
     * @param delta the delta to embed; may be {@code null}
     * @return a populated chunk NBT compound
     */
    @Unique
    private static NbtCompound chunkis$buildChunkNbt(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final NbtCompound nbt = CisNbtUtil.createBaseNbt(pos, GAME_DATA_VERSION);
        CisNbtUtil.putChunkMetadata(nbt, delta);
        CisNbtUtil.putDelta(nbt, delta);
        return nbt;
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
    @Unique
    private static NbtCompound chunkis$serializeEntityNbt(final Entity entity) {
        try (final ErrorReporter.Logging logging = new ErrorReporter.Logging(
                entity.getErrorReporterContext(), Chunkis.LOGGER)) {
            final NbtWriteView writeView = NbtWriteView.create(logging, entity.getRegistryManager());
            entity.writeData(writeView);
            final NbtCompound nbt = writeView.getNbt();
            CisNbtUtil.ensureEntityIdPresent(nbt, entity);
            return nbt;
        } catch (final Exception e) {
            Chunkis.LOGGER.debug(
                    "Chunkis: Skipped entity capture for {} in chunk {}",
                    entity.getType(), entity.getChunkPos(), e
            );
            return null;
        }
    }

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
     * is not useful reconstruction state — preferring it over storage would cause
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
