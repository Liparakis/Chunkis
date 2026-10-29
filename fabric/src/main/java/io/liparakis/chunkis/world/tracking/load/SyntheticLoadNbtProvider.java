package io.liparakis.chunkis.world.tracking.load;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.entity.capture.StoredEntityFingerprints;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.restoration.nbt.PreloadedBlockSections;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import java.util.List;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;

/**
 * Builds the synthetic chunk NBT that carries a Chunkis delta through vanilla's
 * chunk deserialization.
 *
 * <p>Plain logic extracted from the chunk-loading-manager mixin. One instance per
 * {@link ServerWorld}.</p>
 */
public final class SyntheticLoadNbtProvider {

    /**
     * Trace source label for the load path.
     */
    private static final String LOAD_SOURCE = "SyntheticLoadNbtProvider#provide";

    /**
     * World whose chunks this provider loads.
     */
    private final ServerWorld world;

    /**
     * Creates a provider for {@code world}.
     *
     * @param world owning world
     */
    public SyntheticLoadNbtProvider(final ServerWorld world) {
        this.world = world;
    }

    /**
     * Cheaply reports whether Chunkis may have state for {@code chunkPos}: a tracked delta
     * {@link #provide} would use, or a CIS storage entry. Reads no payload.
     *
     * @param chunkPos chunk being loaded
     * @return false when vanilla should load the chunk itself
     */
    public boolean mayProvide(final ChunkPos chunkPos) {
        return !shouldBypassTrackedDelta(getTrackedDelta(chunkPos))
                || FabricCisStorageHelper.getStorage(world).contains(FabricCisStorageHelper.toStoragePos(chunkPos));
    }

    /**
     * Returns CIS-backed load NBT for {@code chunkPos}, or {@code null} when vanilla
     * should load the chunk itself (no restorable Chunkis state).
     *
     * <p>Resolution order: the in-memory tracker delta, then CIS disk storage. The NBT carries Chunkis metadata and delta payloads through
     * vanilla deserialization; a finished chunk also carries its sections
     * ({@link PreloadedBlockSections}). May run on any worker thread, concurrently with
     * other chunks' loads.</p>
     *
     * @param chunkPos chunk being loaded
     * @return synthetic load root, or {@code null} to let vanilla load
     */
    public NbtCompound provide(final ChunkPos chunkPos) {
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = FabricCisStorageHelper.getStorage(world);
        final ChunkDelta<BlockState, NbtCompound> delta = resolveDeltaForLoad(storage, chunkPos);
        if (delta == null) {
            finishBypassedLoad(
                    chunkPos,
                    null,
                    ChunkTraceReason.NEITHER,
                    "load source resolved: no tracker delta and no storage entry",
                    "bypassed synthetic load because tracker and storage were empty"
            );
            return null;
        }

        if (!ChunkDeltaOwnership.hasRestorableChunkisState(delta)) {
            final ChunkTraceReason resolvedReason = shouldBypassTrackedDelta(delta)
                    ? ChunkTraceReason.NEITHER
                    : ChunkTraceReason.TRACKER_MEMORY;
            finishBypassedLoad(
                    chunkPos,
                    delta,
                    resolvedReason,
                    "load source resolved but delta had no restorable Chunkis state",
                    "bypassed synthetic load because delta had no restorable state"
            );
            return null;
        }
        delta.claimOwnership(
                ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE.name(),
                LOAD_SOURCE
        );
        ChunkOwnershipTraceHelper.traceDecision(
                world.getRegistryKey(),
                chunkPos,
                "CLAIMED",
                ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE,
                LOAD_SOURCE,
                delta,
                ChunkMutationTrackingScope.Cause.PASSIVE_LOAD
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_SOURCE_RESOLVED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.CHUNKIS_STORAGE,
                LOAD_SOURCE,
                "load source resolved: using restorable Chunkis delta",
                worldId(),
                debugChunkKey(chunkPos),
                null,
                null,
                delta.isDirty(),
                null
        );

        final CisNbtUtil.LoadChunkNbtResult loadNbt =
                CisNbtUtil.buildLoadChunkNbt(chunkPos, getGameDataVersion(), delta);
        PreloadedBlockSections.embed(world, loadNbt.root(), delta);
        PayloadWatchTracer.traceBlockEntityPresenceInChunkNbt(
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                chunkPos,
                null,
                loadNbt.root(),
                "synthetic-load-root",
                LOAD_SOURCE
        );
        traceLoadNbtSelection(chunkPos, delta, loadNbt);
        return loadNbt.root();
    }

    /**
     * Resolves the delta the load hook should use to build synthetic chunk NBT.
     *
     * <p>A tracked in-memory delta wins. When the tracked delta is too weak to
     * rebuild the chunk, cold storage is consulted instead. A dirty tracked delta is
     * used as it is; its unsaved changes carry over to the reloaded chunk.</p>
     *
     * @param storage  CIS storage backing the world
     * @param chunkPos chunk position being loaded
     * @return resolved load delta, or {@code null} when both tracker and storage
     *         have no useful state
     */
    private ChunkDelta<BlockState, NbtCompound> resolveDeltaForLoad(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos chunkPos
    ) {
        ColdLoadDeltaHandoff.offer(world, chunkPos, null);
        StoredEntityFingerprints.forget(world, chunkPos);
        final ChunkDelta<BlockState, NbtCompound> trackedDelta = getTrackedDelta(chunkPos);
        if (trackedDelta != null && trackedDelta.isBlockPayloadReleased() && trackedDelta.isDirty()) {
            // An unloaded chunk saved from a copy keeps only its recorded edits in its delta: its blocks are the
            // stored entry's with them on top. The delta's other state is newer than the entry (a save may still
            // be on its way, or have failed).
            final ChunkDelta<BlockState, NbtCompound> rebuilt = rebuildFromStoredBlocks(storage, chunkPos,
                    trackedDelta);
            if (rebuilt != null) {
                ColdLoadDeltaHandoff.offer(world, chunkPos, rebuilt);
                return rebuilt;
            }
        }
        if (trackedDelta != null && trackedDelta.isBlockPayloadReleased() && trackedDelta.isDirty()) {
            Chunkis.LOGGER.error("Chunkis: Reloading chunk {} in {} from its stored entry; changes made since its "
                    + "last save could not be captured before it unloaded and are lost", chunkPos,
                    world.getRegistryKey().getValue());
        }
        if (!shouldBypassTrackedDelta(trackedDelta)) {
            // Not saved here: like vanilla's pending writes, unsaved changes stay in memory. The restore keeps the
            // reloaded chunk dirty for them (ChunkRestorationVisitor#finishRestoration), so a normal save writes
            // them. The tracker can drop the entry before conversion; the handoff keeps the convert hook on this
            // delta instead of decoding the stored entry again, on the server thread.
            ColdLoadDeltaHandoff.offer(world, chunkPos, trackedDelta);
            return trackedDelta;
        }

        final var cisPos = FabricCisStorageHelper.toStoragePos(chunkPos);
        if (!storage.contains(cisPos)) {
            return null;
        }
        // Read here, on this worker: a prefetch of the same chunk would only queue the read behind the
        // single prefetch thread and wait for it.
        final ChunkDelta<BlockState, NbtCompound> storedDelta = storage.load(cisPos);
        storedDelta.setSuppressInitialRepopulation(CisNbtUtil.shouldSuppressInitialRepopulation(storedDelta));
        ColdLoadDeltaHandoff.offer(world, chunkPos, storedDelta);
        return storedDelta;
    }

    /**
     * Emits the standard bypass traces for a load that falls back to vanilla
     * behavior and stops the synthetic-load path.
     *
     * @param chunkPos              chunk position being loaded
     * @param delta                 resolved delta, if any
     * @param resolvedReason        load-source reason used in lifecycle tracing
     * @param resolvedMessage       source-resolution message
     * @param transactionEndMessage final load-transaction message
     */
    private void finishBypassedLoad(
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final ChunkTraceReason resolvedReason,
            final String resolvedMessage,
            final String transactionEndMessage
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_SOURCE_RESOLVED,
                ChunkTraceSeverity.INFO,
                resolvedReason,
                LOAD_SOURCE,
                resolvedMessage,
                worldId(),
                debugChunkKey(chunkPos),
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
                LOAD_SOURCE,
                delta,
                ChunkMutationTrackingScope.Cause.PASSIVE_LOAD
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_TX_END,
                ChunkTraceSeverity.INFO,
                resolvedReason,
                LOAD_SOURCE,
                transactionEndMessage,
                worldId(),
                debugChunkKey(chunkPos),
                null,
                null,
                delta != null && delta.isDirty(),
                null
        );
    }

    /**
     * Emits the base-NBT and load-root tracing associated with one synthetic load.
     *
     * @param chunkPos chunk position being loaded
     * @param delta    resolved delta used to build the load root
     * @param loadNbt  synthetic load NBT result
     */
    private void traceLoadNbtSelection(
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final CisNbtUtil.LoadChunkNbtResult loadNbt
    ) {
        final NbtCompound metadata = delta != null ? delta.getChunkMetadata() : null;
        final boolean hasPersistedBaseChunk = delta != null
                && CisNbtUtil.hasPersistedBaseChunkNbt(metadata);
        final boolean authoritativeFullBaseline = delta != null
                && CisNbtUtil.hasFullBlockBaseline(metadata);
        final boolean shouldUsePersistedBaseChunk = delta != null
                && CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(metadata);
        final byte[] baseChunkPayload = metadata != null
                ? metadata.getByteArray(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY)
                  .orElse(new byte[0])
                : new byte[0];

        if (hasPersistedBaseChunk) {
            final NbtCompound baseChunkNbt = CisNbtUtil.extractPersistedBaseChunkNbt(metadata);
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.BASE_NBT_DECODE_STARTED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    LOAD_SOURCE,
                    "base NBT decode started: loadSource="
                            + "delta-present"
                            + ", storageEntryExists="
                            + !delta.isEmpty()
                            + ", metadataKeys="
                            + metadata
                            .getKeys()
                            + ", hasPersistedBaseChunk=" + true
                            + ", shouldUsePersistedBaseChunk=" + shouldUsePersistedBaseChunk
                            + ", authoritativeFullBaseline=" + authoritativeFullBaseline
                            + ", baseChunkPayloadBytes=" + baseChunkPayload.length
                            + ", baseNbtKeys="
                            + (baseChunkNbt != null ? baseChunkNbt.getKeys() : List.of())
                            + ", baseNbtApproxBytes="
                            + (baseChunkNbt != null ? baseChunkNbt.toString()
                                                      .length() : 0),
                    worldId(),
                    debugChunkKey(chunkPos),
                    null,
                    null,
                    delta.isDirty(),
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
                LOAD_SOURCE,
                "base metadata decision: hasPersistedBaseChunk=" + hasPersistedBaseChunk
                        + ", shouldUsePersistedBaseChunk=" + shouldUsePersistedBaseChunk
                        + ", authoritativeFullBaseline=" + authoritativeFullBaseline
                        + ", metadataKeys=" + (metadata != null ? metadata.getKeys() : List.of())
                        + ", baseChunkPayloadBytes=" + baseChunkPayload.length,
                worldId(),
                debugChunkKey(chunkPos),
                null,
                null,
                delta != null && delta.isDirty(),
                null
        );
        PayloadWatchTracer.traceChunkWatchContext(
                worldId(),
                chunkPos,
                null,
                "base-metadata-decision",
                LOAD_SOURCE,
                "base metadata decision: hasPersistedBaseChunk=" + hasPersistedBaseChunk
                        + ", shouldUsePersistedBaseChunk=" + shouldUsePersistedBaseChunk
                        + ", authoritativeFullBaseline=" + authoritativeFullBaseline
                        + ", metadataKeys=" + (metadata != null ? metadata.getKeys() : List.of())
                        + ", baseChunkPayloadBytes=" + baseChunkPayload.length
        );

        if (authoritativeFullBaseline
                && loadNbt.baseChunkUsage() == CisNbtUtil.PersistedBaseChunkUsage.USED) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    LOAD_SOURCE,
                    "authoritative v11 full-baseline delta attempted to use persisted base chunk NBT as block baseline",
                    worldId(),
                    debugChunkKey(chunkPos),
                    null,
                    null,
                    delta.isDirty(),
                    null
            );
        }

        if (loadNbt.baseChunkUsage() == CisNbtUtil.PersistedBaseChunkUsage.USED) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.BASE_NBT_APPLIED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    LOAD_SOURCE,
                    "applied persisted base chunk NBT to synthetic load root",
                    worldId(),
                    debugChunkKey(chunkPos),
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
                    LOAD_SOURCE,
                    authoritativeFullBaseline
                            ? "persisted base chunk NBT was present but skipped because authoritative v11 payload " +
                              "owns blocks"
                            : "persisted base chunk NBT was present but skipped for load root",
                    worldId(),
                    debugChunkKey(chunkPos),
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
                LOAD_SOURCE,
                "built load NBT with baseChunkNbt="
                        + loadNbt.baseChunkUsage()
                        .name()
                        .toLowerCase(Locale.ROOT),
                worldId(),
                debugChunkKey(chunkPos),
                null,
                null,
                delta != null && delta.isDirty(),
                null
        );
    }


    /**
     * Returns the current Minecraft data version id.
     */
    private static int getGameDataVersion() {
        return SharedConstants.getGameVersion()
                .dataVersion()
                .id();
    }

    /**
     * Rebuilds a released tracked delta ({@link ChunkDelta#isBlockPayloadReleased()}): the blocks are the
     * entry's with the delta's recorded edits on top, everything else comes from copies of the tracked delta,
     * which stays as it is.
     *
     * @param storage      CIS storage backing the world
     * @param chunkPos     chunk position being loaded
     * @param trackedDelta tracked delta holding the stored blocks
     * @return the rebuilt delta, or {@code null} when the entry is missing or unreadable
     */
    private ChunkDelta<BlockState, NbtCompound> rebuildFromStoredBlocks(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> trackedDelta
    ) {
        // The edits first: the server thread drops them once a save of the entry wrote them, so read after the
        // copy, the entry holds every edit the copy may lack.
        final ChunkDeltaView<BlockState, NbtCompound> edits = trackedDelta.snapshotView(NbtCompound::copy);
        final CisStorage.LoadResult<BlockState, NbtCompound> stored = storage.loadWithPresence(
                FabricCisStorageHelper.toStoragePos(chunkPos), ChunkTraceStore.nextOperationId("load"));
        if (!stored.storageEntryPresent() || stored.failed()) {
            Chunkis.LOGGER.error("Chunkis: Stored entry of chunk {} in {} is {}; its blocks were only kept there",
                    chunkPos, world.getRegistryKey().getValue(), stored.failed() ? "unreadable" : "missing");
            return null;
        }
        final ChunkDelta<BlockState, NbtCompound> rebuilt = stored.delta();
        edits.forEachBlock((x, y, z, state) -> rebuilt.addBlockChange(x, y, z, state, false));
        rebuilt.adoptNonBlockStateFrom(trackedDelta, NbtCompound::copy);
        rebuilt.markRebuiltFromStoredEntry(edits);
        return rebuilt;
    }

    /**
     * Returns {@code true} when a tracked delta is too weak to rebuild the chunk and
     * storage should be consulted instead (empty, clean, no anchors).
     */
    private static boolean shouldBypassTrackedDelta(final ChunkDelta<BlockState, NbtCompound> delta) {
        // A released block payload holds no blocks; the stored entry does.
        return delta == null || (!delta.isDirty() && delta.isEmpty()) || delta.isBlockPayloadReleased();
    }

    /**
     * Returns the delta currently tracked for {@code pos} (memory and unload cache).
     */
    @SuppressWarnings("unchecked")
    private ChunkDelta<BlockState, NbtCompound> getTrackedDelta(final ChunkPos pos) {
        return (ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker.getDelta(world, pos);
    }

    /**
     * Returns the world registry id string used in traces.
     */
    private String worldId() {
        return world.getRegistryKey()
                .getValue()
                .toString();
    }

    /**
     * Returns the trace key for {@code pos}.
     */
    private static DebugChunkKey debugChunkKey(final ChunkPos pos) {
        return new DebugChunkKey(pos.x, pos.z);
    }
}
