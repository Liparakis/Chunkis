package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.world.restoration.core.PalettedContainerFields;
import io.liparakis.chunkis.mixin.accessor.ChunkSaveFlagAccessor;
import io.liparakis.chunkis.mixin.accessor.WorldChunkAccessor;
import io.liparakis.chunkis.world.restoration.nbt.CisMetadataCodec;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.util.ChunkSectionDebugUtil;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.entity.capture.LiveEntitySnapshotCapture;
import io.liparakis.chunkis.world.restoration.capture.CisSnapshotCapture;
import io.liparakis.chunkis.world.restoration.capture.SnapshotSafetyChecker;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.restoration.nbt.StructureMetadataExtractor;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.ownership.OwnedDeltaAccess;
import it.unimi.dsi.fastutil.shorts.ShortList;
import io.liparakis.chunkis.mixin.accessor.ChunkBlockEntityNbtAccessor;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/**
 * Chunkis' chunk save path for one world: captures the live chunk into its delta
 * and persists it through CIS storage.
 *
 * <p>Plain logic extracted from the chunk-loading-manager mixin, which only
 * delegates here. One instance per {@link ServerLevel}; server thread only.</p>
 */
public final class ChunkSaveCoordinator {

    /**
     * Trace source identification tag label for saving path.
     */
    private static final String SAVE_SOURCE = "ChunkSaveCoordinator#save";


    /**
     * World whose chunks this coordinator saves.
     */
    private final ServerLevel world;

    /**
     * Persistence guard for sparse deltas in {@link #world}.
     */
    private final SaveDeltaGuard saveDeltaGuard;

    /**
     * Unfinished chunks of an authoritative world, kept in vanilla's form.
     */
    private final UnfinishedChunkStore unfinishedChunks;

    /**
     * Creates a coordinator for {@code world}.
     *
     * @param world owning world
     */
    public ChunkSaveCoordinator(final ServerLevel world) {
        this.world = world;
        this.unfinishedChunks = new UnfinishedChunkStore(world);
        this.saveDeltaGuard = new SaveDeltaGuard(world);
    }

    /**
     * Returns the store keeping this world's unfinished chunks.
     *
     * @return the store
     */
    public UnfinishedChunkStore unfinishedChunks() {
        return unfinishedChunks;
    }

    /**
     * Shutdown sequence: drains async saves, synchronously saves every delta still
     * dirty in the tracker, then closes world storage.
     *
     * <p>The async worker is drained first so an older in-flight snapshot cannot
     * overwrite a newer synchronous sweep write.</p>
     */
    public void close() {
        unfinishedChunks.close();
        AsyncCisSaveManager.flushAndClose(world);
        final Map<ChunkPos, ChunkDelta<BlockState, CompoundTag>> pending = GlobalChunkTracker.getPendingDeltas(world);
        forceSaveRemainingDeltas(getStorage(), pending);
        FabricCisStorageHelper.closeStorage(world);
    }

    /**
     * Outcome of a save request, telling the hook whether vanilla should continue.
     */
    public enum SaveOutcome {
        /** Chunkis does not own the chunk; vanilla saves it as usual. */
        VANILLA,
        /** Chunkis handled the save; the chunk was vanilla-dirty. */
        HANDLED_DIRTY,
        /** Chunkis handled the save; the chunk was not vanilla-dirty. */
        HANDLED_CLEAN
    }

    /**
     * Captures and persists {@code chunkToSave} if Chunkis owns it.
     *
     * <p>Vanilla reaches this through {@code save(Chunk)} from autosave (which keeps
     * its own throttle), chunk unload and the shutdown flush.</p>
     *
     * @param chunkToSave the chunk vanilla is saving
     * @return whether vanilla should continue, or whether the chunk was vanilla-dirty
     */
    public SaveOutcome save(final ChunkAccess chunkToSave) {
        final ChunkPos pos = chunkToSave.getPos();
        final String operationId = ChunkTraceStore.nextOperationId("save");
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SAVE_SOURCE,
                "save hook requested",
                world.dimension()
                        .identifier()
                        .toString(),
                debugChunkKey(pos),
                null,
                operationId,
                null,
                null
        );
        final ChunkAccess chunk = chunkToSave instanceof ImposterProtoChunk wrapper
                ? wrapper.getWrapped()
                : chunkToSave;
        // Read first: vanilla's flag is the wrapped chunk's, and says whether anything but entities changed.
        final boolean vanillaUnsaved = !(chunk instanceof ChunkSaveFlagAccessor flag) || flag.chunkis$vanillaNeedsSaving();
        ChunkDelta<BlockState, CompoundTag> delta = resolveDeltaForSave(chunk, pos);
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            traceBypassedSave(pos, delta, chunk, operationId);
            if (!MigrationStateServiceHolder.isAuthoritative(world.dimension())) {
                return SaveOutcome.VANILLA;
            }
            if (!(chunk instanceof LevelChunk worldChunk) || !isSettled(worldChunk)
                    || SnapshotSafetyChecker.isSnapshotUnsafe(worldChunk)) {
                // Still generating, post-processing has not run, or its blocks cannot be snapshotted right now:
                // kept as vanilla keeps it, so the next load continues instead of starting over.
                world.getPoiManager()
                        .flush(pos);
                return chunkToSave.tryMarkSaved() && unfinishedChunks.save(chunk)
                        ? SaveOutcome.HANDLED_DIRTY
                        : SaveOutcome.HANDLED_CLEAN;
            }
            // Reading a stored chunk back is far cheaper than generating it again. The snapshot below stores
            // every block, block entity, light and biome and the pending ticks, so no vanilla copy is needed.
            delta = OwnedDeltaAccess.getOrClaim(worldChunk, ChunkTraceReason.SETTLED_CHUNK_BASELINE, SAVE_SOURCE);
            // A chunk of only air captures no block; it must still be written so it is not generated again.
            delta.markDirty(SAVE_SOURCE);
        }
        // From now on the chunk's Chunkis entry is what loads; an unfinished copy would only take up space.
        unfinishedChunks.discard(pos);
        world.getPoiManager()
                .flush(pos);
        ChunkOwnershipTraceHelper.traceDecision(
                world.dimension(),
                pos,
                "CLAIMED",
                ChunkTraceReason.valueOf(delta.getOwnershipReason()),
                SAVE_SOURCE,
                delta,
                null
        );
        PayloadWatchTracer.traceDeltaStage(
                world.dimension()
                        .identifier()
                        .toString(),
                pos,
                delta,
                operationId,
                ChunkTraceEventType.WATCH_CAPTURED,
                "save-start",
                SAVE_SOURCE,
                "delta entered save transaction",
                null
        );

        // A released block payload stays released: the blocks are copied and saved from the copy, off the server
        // thread. Once the chunk unloads, a later save or a reload reads them back from the stored entry with the
        // recorded edits on top (ChunkDelta#isBlockPayloadReleased).
        final boolean loadedToWorld = isLoadedToWorld(chunk);
        final boolean deferBlocks = delta.isBlockPayloadReleased() && PalettedContainerFields.AVAILABLE;
        if (!deferBlocks) {
            delta = captureSnapshot(chunk, delta, operationId);
        }
        delta = captureStructureMetadata(chunk, delta);
        if (!deferBlocks || !loadedToWorld) {
            // A chunk that stays loaded gets its entities from vanilla's own entity saves (on entity chunk
            // unload and world save, through VanillaEntitySaveCapture), the same schedule vanilla persists them
            // on; re-serializing every entity on each block autosave cost more than the rest of the save.
            delta = captureLiveEntities(chunk, delta, operationId);
        }
        PalettedContainer<BlockState>[] deferredBlocks = null;
        if (delta != null && delta.isBlockPayloadReleased() && delta.isDirty()) {
            deferredBlocks = deferBlocks
                    ? captureDeferredBlocks(chunk, delta, !vanillaUnsaved && holdsNonBlockState(delta), operationId)
                    : null;
            if (deferredBlocks == null) {
                // Unloading, or only the entity or structure capture changed it after the block capture saw it
                // clean.
                delta = captureSnapshot(chunk, delta, operationId);
            }
        }

        if (delta != null && !delta.isDirty() && !loadedToWorld && deferBlocks && chunk instanceof LevelChunk unloading
                && delta.getWrittenBlocksPrint() != null) {
            // Unloading clean: nothing to save, but keep its blocks for a quick reload. Vanilla has already let go
            // of this chunk, so its containers are taken, not copied. They are checked against the content the
            // stored entry was written from before any load uses them.
            final long[] print = delta.getWrittenBlocksPrint();
            final int blockCount = ChunkSectionDebugUtil.countNonAirBlocks(unloading);
            RecentChunkBlocks.of(world.dimension()).putToVerify(FabricCisStorageHelper.toStoragePos(pos),
                    new long[]{print[2], print[3]}, new long[]{print[0], print[1]}, takeSections(unloading),
                    world.getMinY(), blockCount);
        }
        if (delta != null && !delta.isDirty()) {
            PayloadWatchTracer.traceDeltaStage(
                    world.dimension()
                            .identifier()
                            .toString(),
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
            queueDirtyDelta(getStorage(), pos, delta, operationId, chunk, deferredBlocks);
        }

        if (vanillaUnsaved && chunk instanceof LevelChunk live && SnapshotSafetyChecker.isSnapshotUnsafe(live)) {
            // The capture above was skipped, so what vanilla saw change is not in the delta: keep vanilla's flag for
            // the next save to capture it, which a cleared flag would leave to the entity-only path, keeping the old
            // block entities, light and ticks.
            return SaveOutcome.HANDLED_CLEAN;
        }
        // Vanilla's return contract: true once per vanilla-dirty chunk. It drives the 10s
        // autosave throttle and terminates the flush loop (`while any save returned true`);
        // returning "delta still dirty" would spin there, since async completion runs later.
        return chunkToSave.tryMarkSaved() ? SaveOutcome.HANDLED_DIRTY : SaveOutcome.HANDLED_CLEAN;
    }

    /**
     * Returns whether a chunk Chunkis does not own can be stored as it is: it is fully generated and its
     * post-processing ran, so no generated block or block entity is still waiting to be finished. Its pending
     * ticks are stored with it.
     *
     * @param chunk chunk being saved
     * @return {@code true} when its blocks can be stored as they are
     */
    private static boolean isSettled(final LevelChunk chunk) {
        if (!ChunkStatus.FULL.equals(chunk.getPersistedStatus())
                || !((ChunkBlockEntityNbtAccessor) chunk).chunkis$getBlockEntityNbts().isEmpty()) {
            return false;
        }
        for (final ShortList positions : chunk.getPostProcessing()) {
            if (positions != null && !positions.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts a ChunkPos into a DebugChunkKey.
     *
     * @param pos the coordinates to map
     * @return a mapped DebugChunkKey
     */
    private static DebugChunkKey debugChunkKey(final ChunkPos pos) {
        return new DebugChunkKey(pos.x(), pos.z());
    }

    /**
     * Returns the delta attached to {@code chunk} via {@link ChunkisDeltaDuck},
     * or {@code null} if the chunk is {@code null} or does not implement the
     * interface. Fallback for chunks absent from the global tracker.
     *
     * @param chunk the chunk to inspect; may be {@code null}
     * @return the attached delta, or {@code null}
     */
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, CompoundTag> getChunkDelta(final ChunkAccess chunk) {
        return chunk instanceof ChunkisDeltaDuck duck
                ? (ChunkDelta<BlockState, CompoundTag>) duck.chunkis$getDelta()
                : null;
    }


    /**
     * Writes {@code metadata} into {@code delta}, preferring the pre-encoded path
     * for performance. Falls back to a direct set if encoding fails so structure
     * metadata is never silently lost.
     *
     * @param delta    the delta to update
     * @param metadata the metadata compound to install
     * @param pos      the chunk position used for the fallback warning log
     */
    private static void updateDeltaMetadata(
            final ChunkDelta<BlockState, CompoundTag> delta,
            final CompoundTag metadata,
            final ChunkPos pos) {
        try {
            delta.updateChunkMetadataWithEncodedPayload(
                    metadata, CisMetadataCodec.serializeRawPayload(metadata));
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
     * Claims ownership for a delta created by save-time live entity capture.
     *
     * <p>Entity capture is allowed to return {@code null} when snapshot safety
     * vetoes the capture and there was no pre-existing delta to preserve. The save
     * path must keep that bypass shape instead of dereferencing {@code null}.</p>
     *
     * @param delta  captured delta, or {@code null} when capture was skipped
     * @param reason ownership reason to record
     * @param source source label to record alongside the claim
     * @return the same delta instance, or {@code null} when no delta exists
     */
    @SuppressWarnings("SameParameterValue")
    private static ChunkDelta<BlockState, CompoundTag> claimEntityCapturedDelta(
            final ChunkDelta<BlockState, CompoundTag> delta,
            final String reason,
            final String source
    ) {
        if (delta == null) {
            return null;
        }
        delta.claimOwnership(reason, source);
        return delta;
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
    private ChunkDelta<BlockState, CompoundTag> resolveDeltaForSave(
            final ChunkAccess chunk,
            final ChunkPos pos
    ) {
        ChunkDelta<BlockState, CompoundTag> delta = getActiveDelta(pos);
        final ChunkDelta<BlockState, CompoundTag> attachedDelta = getChunkDelta(chunk);
        delta = preserveMigratedAuthoritativeBlockEntities(pos, chunk, delta, attachedDelta);
        if (delta == null) {
            delta = attachedDelta;
        }
        if (ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            return delta;
        }
        if (!(chunk instanceof LevelChunk worldChunk)) {
            return delta;
        }
        if (LiveEntitySnapshotCapture.countSavableLiveEntities(world, worldChunk) == 0) {
            return delta;
        }

        delta = captureLiveEntities(chunk, delta, null);
        return claimEntityCapturedDelta(
                delta,
                ChunkTraceReason.EXPLICIT_CHUNKIS_MUTATION.name(),
                SAVE_SOURCE + "#entityCapture"
        );
    }

    /**
     * Prevents migrated authoritative block-entity payloads from being overwritten
     * by an empty save candidate before vanilla has materialized live block entities.
     */
    private ChunkDelta<BlockState, CompoundTag> preserveMigratedAuthoritativeBlockEntities(
            final ChunkPos pos,
            final ChunkAccess chunk,
            final ChunkDelta<BlockState, CompoundTag> saveDelta,
            final ChunkDelta<BlockState, CompoundTag> attachedDelta
    ) {
        if (!(chunk instanceof LevelChunk worldChunk)) {
            return saveDelta;
        }

        final ChunkDelta<BlockState, CompoundTag> delta = saveDelta != null ? saveDelta : attachedDelta;
        if (delta == null
                || !CisNbtUtil.isMigratedAuthoritativeChunk(delta.getChunkMetadata())
                || !worldChunk.getBlockEntities()
                .isEmpty()
                || !delta.getBlockEntities()
                .isEmpty()) {
            return saveDelta;
        }

        final ChunkDelta<BlockState, CompoundTag> sourceDelta =
                resolveAuthoritativeBlockEntitySource(pos, attachedDelta, delta);
        if (sourceDelta == null || sourceDelta.getBlockEntities()
                .isEmpty()) {
            return saveDelta;
        }

        ChunkRestorer.copyBlockEntityPayloads(sourceDelta, delta);
        return delta;
    }

    /**
     * Resolves a stronger migrated authoritative source for block-entity payloads.
     */
    private ChunkDelta<BlockState, CompoundTag> resolveAuthoritativeBlockEntitySource(
            final ChunkPos pos,
            final ChunkDelta<BlockState, CompoundTag> attachedDelta,
            final ChunkDelta<BlockState, CompoundTag> currentDelta
    ) {
        if (attachedDelta != null
                && attachedDelta != currentDelta
                && !attachedDelta.getBlockEntities()
                .isEmpty()) {
            return attachedDelta;
        }

        try {
            final ChunkDelta<BlockState, CompoundTag> persisted =
                    getStorage().loadWithoutClearing(FabricCisStorageHelper.toStoragePos(pos));
            return !persisted.getBlockEntities()
                    .isEmpty() ? persisted : null;
        } catch (final Exception e) {
            Chunkis.LOGGER.warn(
                    "Chunkis: Failed to reload migrated block entities for chunk {} during save preservation",
                    pos,
                    e
            );
            return null;
        }
    }

    /**
     * Traces why a save stayed on the path Chunkis does not own, including the
     * assertion that such a chunk should have no dirty delta or live entities.
     *
     * @param pos         chunk position being saved
     * @param delta       resolved delta, if any
     * @param chunk       resolved chunk, if any
     * @param operationId trace correlation ID
     */
    private void traceBypassedSave(
            final ChunkPos pos,
            final ChunkDelta<BlockState, CompoundTag> delta,
            final ChunkAccess chunk,
            final String operationId
    ) {
        if (!ChunkisDebugConfig.allows(ChunkisDebugDomain.ASSERTIONS, ChunkTraceSeverity.ERROR)) {
            return;
        }
        // The entity-column scan only feeds the assertion trace, so it is skipped when tracing is off.
        final int liveSavableEntities = chunk instanceof LevelChunk worldChunk
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
                    world.dimension()
                            .identifier()
                            .toString(),
                    debugChunkKey(pos),
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
                            + " savable live entity/entities before Chunkis entity capture",
                    world.dimension()
                            .identifier()
                            .toString(),
                    debugChunkKey(pos),
                    null,
                    operationId,
                    delta != null && delta.isDirty(),
                    null
            );
        }
        ChunkOwnershipTraceHelper.traceDecision(
                world.dimension(),
                pos,
                "BYPASSED",
                ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED,
                SAVE_SOURCE,
                delta,
                null
        );
    }


    /**
     * Returns the active (dirty-map only) delta for {@code pos}, or {@code null}.
     * Used by the save path, which prefers active deltas over the duck fallback.
     *
     * @param pos the chunk position
     * @return the active dirty delta, or {@code null}
     */
    @SuppressWarnings("unchecked")
    private ChunkDelta<BlockState, CompoundTag> getActiveDelta(final ChunkPos pos) {
        return (ChunkDelta<BlockState, CompoundTag>) GlobalChunkTracker.getActiveDelta(world, pos);
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
    private ChunkDelta<BlockState, CompoundTag> captureStructureMetadata(
            final ChunkAccess chunk,
            final ChunkDelta<BlockState, CompoundTag> existingDelta) {
        if (chunk == null) {
            return existingDelta;
        }

        final CompoundTag structureData = extractStructureMetadata(chunk);
        final boolean hasStructures = CisNbtUtil.hasStructureData(structureData);

        if (existingDelta == null && !hasStructures) {
            return null;
        }

        final ChunkDelta<BlockState, CompoundTag> delta =
                existingDelta != null ? existingDelta : new ChunkDelta<>();
        final CompoundTag existingMetadata = delta.getChunkMetadata();

        delta.setSuppressInitialRepopulation(true);

        if (!hasStructures) {
            return delta;
        }

        final boolean portalChunk = chunk instanceof LevelChunk worldChunk
                ? io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil.hasPortalBlocks(worldChunk)
                : CisNbtUtil.hasPersistedPortalChunk(existingMetadata);
        // Structures rarely change after generation; skip deserializing the base chunk to rebuild identical metadata.
        if (CisNbtUtil.isStructureMetadataCurrent(existingMetadata, structureData, portalChunk)) {
            return delta;
        }
        final CompoundTag metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                structureData,
                true,
                CisNbtUtil.hasFullBlockBaseline(existingMetadata),
                CisNbtUtil.extractPersistedBaseChunkNbt(existingMetadata),
                portalChunk
        );
        CisNbtUtil.preserveCarriedMetadata(existingMetadata, metadata);

        if (java.util.Objects.equals(metadata, existingMetadata)) {
            return delta;
        }

        updateDeltaMetadata(delta, metadata, chunk.getPos());
        return delta;
    }

    /**
     * Takes the section block containers of a chunk vanilla is done with, {@code null} for sections of only air.
     *
     * @param chunk chunk being unloaded
     * @return its containers by section index
     */
    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState>[] takeSections(final LevelChunk chunk) {
        final LevelChunkSection[] sections = chunk.getSections();
        final PalettedContainer<BlockState>[] taken = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null && !sections[i].hasOnlyAir()) {
                taken[i] = sections[i].getStates();
            }
        }
        return taken;
    }

    /**
     * Reports whether a chunk is still part of the world; vanilla clears this just before a chunk's unload save.
     *
     * @param chunk chunk being saved
     * @return true for a loaded world chunk that is not being unloaded
     */
    private static boolean isLoadedToWorld(final ChunkAccess chunk) {
        return chunk instanceof WorldChunkAccessor accessor && accessor.chunkis$isLoadedToWorld();
    }

    /**
     * Captures a snapshot of a chunk whose delta released its block payload, copying its blocks instead of
     * capturing them, when the chunk can be snapshotted now.
     *
     * @param chunk                chunk being saved
     * @param delta                its released delta
     * @param nonBlockStateCurrent whether the delta's non-block state is still the chunk's
     * @param operationId          active save operation ID
     * @return copied block containers by section index, or {@code null} when no snapshot could be taken
     */
    private static PalettedContainer<BlockState>[] captureDeferredBlocks(final ChunkAccess chunk,
            final ChunkDelta<BlockState, CompoundTag> delta, final boolean nonBlockStateCurrent,
            final String operationId) {
        if (!(chunk instanceof LevelChunk worldChunk)
                || !ChunkStatus.FULL.equals(chunk.getPersistedStatus())
                || SnapshotSafetyChecker.isSnapshotUnsafe(worldChunk)) {
            return null;
        }
        return CisSnapshotCapture.captureDeferringBlocks(worldChunk, delta, nonBlockStateCurrent, operationId);
    }

    /**
     * Reports whether a delta holds a complete capture of everything but blocks and entities (block entities, light,
     * biomes, heightmaps, ticks), which a save can keep when vanilla saw none of it change.
     *
     * @param delta the delta
     * @return true when its metadata has the stored sections and heightmaps a full capture writes
     */
    private static boolean holdsNonBlockState(final ChunkDelta<BlockState, CompoundTag> delta) {
        final CompoundTag metadata = delta.getChunkMetadata();
        return metadata != null && metadata.contains(CisNbtUtil.STORED_SECTIONS_KEY)
                && metadata.contains(CisNbtUtil.STORED_HEIGHTMAPS_KEY);
    }

    /**
     * Captures an authoritative CIS snapshot from the live chunk.
     *
     * @param chunk         live chunk object
     * @param existingDelta existing block delta
     * @param operationId   active load/save operation ID
     * @return updated block delta
     */
    private ChunkDelta<BlockState, CompoundTag> captureSnapshot(
            final ChunkAccess chunk,
            final ChunkDelta<BlockState, CompoundTag> existingDelta,
            final String operationId) {
        if (!(chunk instanceof LevelChunk worldChunk)
                || !ChunkStatus.FULL.equals(chunk.getPersistedStatus())) {
            return existingDelta;
        }
        if (SnapshotSafetyChecker.isSnapshotUnsafe(worldChunk)) {
            PayloadWatchTracer.traceDeltaStage(
                    world.dimension()
                            .identifier()
                            .toString(),
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

        final ChunkDelta<BlockState, CompoundTag> delta = existingDelta != null
                ? existingDelta
                : new ChunkDelta<>(BlockState::isAir);
        return CisSnapshotCapture.capture(worldChunk, delta, operationId);
    }

    /**
     * Captures the current non-player entity set for the chunk before persistence.
     *
     * <p>Chunkis snapshots live entities into the runtime delta during normal save.
     * This is separate from the offline MCA migration path, where vanilla entity
     * storage remains authoritative for already-persisted mobs.</p>
     *
     * @param chunk         the chunk being saved; may be {@code null}
     * @param existingDelta the existing delta; may be {@code null}
     * @param operationId   active load/save operation ID
     * @return the updated delta, the original delta, or {@code null}
     */
    private ChunkDelta<BlockState, CompoundTag> captureLiveEntities(
            final ChunkAccess chunk,
            final ChunkDelta<BlockState, CompoundTag> existingDelta,
            final String operationId) {
        if (!(chunk instanceof LevelChunk worldChunk)) {
            return existingDelta;
        }
        return LiveEntitySnapshotCapture.capture(
                world,
                worldChunk,
                existingDelta,
                operationId,
                "ChunkSaveCoordinator#captureLiveEntities"
        );
    }

    /**
     * Queues {@code delta} for async compression and disk write if it is dirty and
     * passes the persistence guard.
     *
     * <p>Only the normal save hook uses this path. Load-path and shutdown safety
     * flushes remain synchronous.</p>
     *
     * @param storage     the storage to save into
     * @param pos         the chunk position
     * @param delta       the delta to queue; may be {@code null}
     * @param operationId active load/save operation ID
     */
    private void queueDirtyDelta(
            final CisStorage<Block, BlockState, Property<?>, CompoundTag> storage,
            final ChunkPos pos,
            final ChunkDelta<BlockState, CompoundTag> delta,
            final String operationId,
            final ChunkAccess chunk,
            final PalettedContainer<BlockState>[] deferredBlocks) {
        ChunkDelta<BlockState, CompoundTag> deltaToQueue = saveDeltaGuard.prepareDeltaForQueueing(pos, delta, operationId);
        if (deltaToQueue == null) {
            return;
        }
        deltaToQueue = saveDeltaGuard.prepareDeltaForPersistence(
                pos,
                deltaToQueue,
                "save-hook-async",
                "ChunkSaveCoordinator#queueDirtyDelta",
                operationId,
                chunk
        );
        if (deltaToQueue == null) {
            PayloadWatchTracer.traceDeltaStage(
                    world.dimension()
                            .identifier()
                            .toString(),
                    pos,
                    delta,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "save-rejected-sparse",
                    "ChunkSaveCoordinator#queueDirtyDelta",
                    "delta was rejected by sparse persistence guard; async save not queued",
                    null
            );
            return;
        }

        // Vanilla marks a chunk no longer loaded to the world before its unload save.
        AsyncCisSaveManager.submit(world, storage, pos, deltaToQueue, deferredBlocks, !isLoadedToWorld(chunk),
                operationId);
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
    private void forceSaveRemainingDeltas(
            final CisStorage<Block, BlockState, Property<?>, CompoundTag> storage,
            final Map<ChunkPos, ChunkDelta<BlockState, CompoundTag>> pending) {
        if (pending.isEmpty()) {
            return;
        }
        final Map<ChunkPos, ChunkDelta<BlockState, CompoundTag>> toSave = new LinkedHashMap<>();
        for (final Map.Entry<ChunkPos, ChunkDelta<BlockState, CompoundTag>> entry : pending.entrySet()) {
            final ChunkDelta<BlockState, CompoundTag> delta = entry.getValue();
            if (delta == null || !delta.isDirty()) {
                continue;
            }
            final ChunkDelta<BlockState, CompoundTag> deltaToSave = saveDeltaGuard.prepareDeltaForPersistence(
                    entry.getKey(),
                    delta,
                    "close-sweep",
                    "ChunkSaveCoordinator#forceSaveRemainingDeltas",
                    ChunkTraceStore.nextOperationId("save"),
                    null
            );
            if (deltaToSave != null) {
                toSave.put(entry.getKey(), deltaToSave);
            }
        }
        if (toSave.isEmpty()) {
            return;
        }
        // Counted after filtering: the tracker also holds clean deltas, which have nothing to save.
        Chunkis.LOGGER.warn("Chunkis [CLOSE]: Force-saving {} remaining dirty delta(s) for {}", toSave.size(),
                world.dimension().identifier());
        FabricCisStorageHelper.saveTrackedDeltas(world, storage, toSave);
    }

    /**
     * Extracts structure metadata from a live chunk.
     *
     * <p>The direct extractor is preferred to avoid full vanilla serialization. If
     * it fails, {@link SerializableChunkData} is used as a safe fallback.</p>
     *
     * @param chunk the chunk to inspect
     * @return the structure metadata compound
     */
    private CompoundTag extractStructureMetadata(final ChunkAccess chunk) {
        try {
            return StructureMetadataExtractor.extract(world, chunk);
        } catch (final Exception e) {
            Chunkis.LOGGER.warn(
                    "Chunkis: Direct structure metadata extraction failed for chunk {}, " +
                            "falling back to SerializedChunk",
                    chunk.getPos(), e
            );
            return SerializableChunkData.copyOf(world, chunk)
                    .structureData();
        }
    }

    /**
     * Returns the CIS storage instance for {@link #world}.
     *
     * @return the CIS storage for the owning world
     */
    private CisStorage<Block, BlockState, Property<?>, CompoundTag> getStorage() {
        return FabricCisStorageHelper.getStorage(world);
    }
}
