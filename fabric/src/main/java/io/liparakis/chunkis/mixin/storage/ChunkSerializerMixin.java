package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.ChunkSectionDebugUtil;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.WrapperProtoChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@link SerializedChunk#convert} to attach Chunkis delta data to
 * freshly converted {@link ProtoChunk} instances.
 *
 * <p>This runs at {@code RETURN}, after vanilla has converted serialized chunk
 * NBT into a proto chunk. Chunkis then loads the matching delta using a
 * memory-first, disk-fallback strategy and attaches it through
 * {@link ChunkisDeltaDuck}.</p>
 *
 * <p>For synthetic empty chunks (no persisted base chunk NBT), the status is
 * reset to {@link ChunkStatus#EMPTY} so vanilla worldgen can regenerate terrain
 * and Chunkis can replay the delta on top of fresh terrain later.</p>
 *
 * <p>Snapshot deltas are attached to the proto chunk and the status is reset to
 * {@link ChunkStatus#EMPTY} so vanilla generation runs before Chunkis restores the
 * authoritative saved snapshot into the promoted world chunk.</p>
 */
@Mixin(SerializedChunk.class)
public class ChunkSerializerMixin {

    /**
     * Trace source identification tag label.
     */
    @Unique
    private static final String SOURCE = "ChunkSerializerMixin";

    /**
     * Weak map marking serialized chunk instances derived from synthetic load paths.
     */
    @Unique
    private static final Set<SerializedChunk> chunkis$syntheticLoadMarker =
            chunkis$newIdentityMarkerSet();

    /**
     * Default constructor for ChunkSerializerMixin.
     */
    public ChunkSerializerMixin() {
    }

    /**
     * Injects into SerializedChunk#fromNbt to detect and mark synthetic Chunkis load requests.
     *
     * @param world            the height limit view
     * @param containerFactory the palettes factory
     * @param nbt              the source NBT compound
     * @param cir              callback info returnable
     */
    @Inject(method = "fromNbt", at = @At("RETURN"))
    private static void chunkis$onFromNbt(
            final net.minecraft.world.HeightLimitView world,
            final net.minecraft.world.chunk.PalettesFactory containerFactory,
            final NbtCompound nbt,
            final CallbackInfoReturnable<SerializedChunk> cir) {
        final SerializedChunk serializedChunk = cir.getReturnValue();
        if (serializedChunk == null) {
            return;
        }

        if (CisNbtUtil.hasSyntheticChunkisLoadMarker(nbt)) {
            chunkis$syntheticLoadMarker.add(serializedChunk);
        }
    }

    /**
     * Checks and removes the synthetic load marker mapping the serialized chunk.
     *
     * @param serializedChunk target chunk key
     * @return true if marked synthetic
     */
    @Unique
    private static boolean chunkis$consumeSyntheticLoadMarker(final SerializedChunk serializedChunk) {
        return chunkis$syntheticLoadMarker.remove(serializedChunk);
    }

    /**
     * Creates an identity-based synchronized marker set.
     *
     * <p>SerializedChunk hashCode walks deep NBT state, which is wasted work for
     * one-shot synthetic load markers that only need object identity.</p>
     *
     * @param <T> marker element type
     * @return synchronized identity set
     */
    @Unique
    private static <T> Set<T> chunkis$newIdentityMarkerSet() {
        return Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Loads, attaches, and applies the Chunkis delta for a converted proto chunk.
     *
     * <p>If no meaningful delta exists the proto chunk is left exactly as vanilla
     * produced it.</p>
     *
     * <p>Restore order:</p>
     * <ol>
     *   <li>Load delta (memory -> disk).</li>
     *   <li>Trace log.</li>
     *   <li>Set suppression flag from persisted metadata.</li>
     *   <li>Attach delta to chunk via {@link ChunkisDeltaDuck}.</li>
     *   <li>Reset status to {@link ChunkStatus#EMPTY} so vanilla worldgen regenerates
     *       terrain before snapshot replay.</li>
     * </ol>
     *
     * @param world       the server world
     * @param pos         the chunk position
     * @param chunk       the converted proto chunk
     * @param operationId active load session operation ID
     */
    @Unique
    private static void chunkis$restoreChunkDelta(
            final ServerWorld world,
            final ChunkPos pos,
            final ProtoChunk chunk,
            final String operationId) {
        final ResolvedDelta resolved = chunkis$loadDelta(world, pos, operationId);
        final boolean traceLifecycle =
                ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO);

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_SOURCE_RESOLVED,
                ChunkTraceSeverity.INFO,
                resolved.reason(),
                SOURCE + "#chunkis$loadDelta",
                "load source resolved",
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(pos),
                null,
                operationId,
                resolved.delta() != null && resolved.delta()
                        .isDirty(),
                null
        );

        if (chunkis$isDeltaAbsent(resolved.delta())
                || !ChunkDeltaOwnership.hasRestorableChunkisState(resolved.delta())) {
            ChunkOwnershipTraceHelper.traceDecision(
                    world.getRegistryKey(),
                    pos,
                    "BYPASSED",
                    ChunkTraceReason.PASSIVE_VANILLA_LOAD,
                    SOURCE + "#chunkis$restoreChunkDelta",
                    resolved.delta(),
                    ChunkMutationTrackingScope.Cause.PASSIVE_LOAD
            );
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.LOAD_TX_END,
                    ChunkTraceSeverity.INFO,
                    resolved.reason(),
                    SOURCE + "#chunkis$restoreChunkDelta",
                    "no meaningful delta to attach",
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(pos),
                    null,
                    operationId,
                    null,
                    null
            );
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta = resolved.delta();
        chunkis$traceBaseMetadataAfterDecode(world, pos, delta, operationId);
        delta.claimOwnership(
                ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE.name(),
                SOURCE + "#chunkis$restoreChunkDelta"
        );
        ChunkOwnershipTraceHelper.traceDecision(
                world.getRegistryKey(),
                pos,
                "CLAIMED",
                ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE,
                SOURCE + "#chunkis$restoreChunkDelta",
                delta,
                ChunkMutationTrackingScope.Cause.PASSIVE_LOAD
        );
        PendingChunkMutationSuppression.begin(
                world.getRegistryKey(),
                pos,
                ChunkMutationTrackingScope.Cause.PASSIVE_LOAD,
                SOURCE + "#chunkis$restoreChunkDelta"
        );
        delta.setSuppressInitialRepopulation(CisNbtUtil.shouldSuppressInitialRepopulation(delta));
        final boolean usePersistedBaseChunkForBlocks =
                CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(delta.getChunkMetadata());
        if (traceLifecycle) {
            final int protoSectionsBeforeRestore = ChunkSectionDebugUtil.countNonEmptySections(chunk);
            final int protoNonAirBlocksAfterBase = ChunkSectionDebugUtil.countNonAirBlocks(chunk);
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.PROTO_CHUNK_SECTIONS_BEFORE_RESTORE,
                    ChunkTraceSeverity.INFO,
                    resolved.reason(),
                    SOURCE + "#chunkis$restoreChunkDelta",
                    "proto sections before restore: " + ChunkSectionDebugUtil.summarize(chunk),
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(pos),
                    null,
                    operationId,
                    delta.isDirty(),
                    null
            );
            if (usePersistedBaseChunkForBlocks) {
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.CHUNK_LIFECYCLE,
                        protoSectionsBeforeRestore > 0
                                ? ChunkTraceEventType.BASE_NBT_DECODE_COMPLETED
                                : ChunkTraceEventType.BASE_NBT_DECODE_FAILED,
                        protoSectionsBeforeRestore > 0
                                ? ChunkTraceSeverity.INFO
                                : ChunkTraceSeverity.ERROR,
                        protoSectionsBeforeRestore > 0
                                ? ChunkTraceReason.NONE
                                : ChunkTraceReason.DECODE_FAILED,
                        SOURCE + "#chunkis$restoreChunkDelta",
                        "base NBT decode "
                                + (protoSectionsBeforeRestore > 0 ? "completed" : "failed")
                                + ": sections=" + protoSectionsBeforeRestore
                                + ", nonAirBlocks=" + protoNonAirBlocksAfterBase,
                        world.getRegistryKey()
                                .getValue()
                                .toString(),
                        DebugChunkKeys.of(pos),
                        null,
                        operationId,
                        delta.isDirty(),
                        null
                );
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.CHUNK_LIFECYCLE,
                        ChunkTraceEventType.PROTO_CHUNK_SECTIONS_AFTER_BASE,
                        protoSectionsBeforeRestore > 0 ? ChunkTraceSeverity.INFO : ChunkTraceSeverity.ERROR,
                        protoSectionsBeforeRestore > 0 ? ChunkTraceReason.NONE : ChunkTraceReason.DECODE_FAILED,
                        SOURCE + "#chunkis$restoreChunkDelta",
                        "proto summary after base decode: " + ChunkSectionDebugUtil.summarize(chunk),
                        world.getRegistryKey()
                                .getValue()
                                .toString(),
                        DebugChunkKeys.of(pos),
                        null,
                        operationId,
                        delta.isDirty(),
                        null
                );
            }
        }

        chunkis$attachDeltaToChunk(
                chunk,
                delta,
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                operationId,
                resolved.reason() == ChunkTraceReason.CHUNKIS_STORAGE
        );
        if (chunkis$restoreWrappedFullChunk(world, chunk, delta, operationId,
                resolved.reason() == ChunkTraceReason.CHUNKIS_STORAGE)) {
            PendingChunkMutationSuppression.end(
                    world.getRegistryKey(),
                    pos,
                    SOURCE + "#chunkis$restoreChunkDelta#wrappedFullChunk"
            );
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.LOAD_TX_END,
                    ChunkTraceSeverity.INFO,
                    resolved.reason(),
                    SOURCE + "#chunkis$restoreChunkDelta",
                    "attached delta to wrapped live chunk and restored immediately",
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    DebugChunkKeys.of(pos),
                    null,
                    operationId,
                    delta.isDirty(),
                    null
            );
            return;
        }
        final boolean resetProtoChunkToEmpty = chunkis$shouldResetProtoChunkToEmpty(delta.getChunkMetadata());
        if (resetProtoChunkToEmpty) {
            if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.ASSERTIONS,
                        ChunkTraceEventType.ASSERTION_FAILED,
                        ChunkTraceSeverity.ERROR,
                        ChunkTraceReason.RESET_EMPTY_WITHOUT_OWNERSHIP,
                        SOURCE + "#chunkis$restoreChunkDelta",
                        "attempted to reset proto chunk to EMPTY without ownership",
                        world.getRegistryKey()
                                .getValue()
                                .toString(),
                        DebugChunkKeys.of(pos),
                        null,
                        operationId,
                        delta.isDirty(),
                        null
                );
            }
            ChunkOwnershipTraceHelper.traceDecision(
                    world.getRegistryKey(),
                    pos,
                    "CLAIMED",
                    ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE,
                    SOURCE + "#chunkis$restoreChunkDelta#setStatusEmpty",
                    delta,
                    ChunkMutationTrackingScope.initialCauseForLoad(delta)
            );
            chunk.setStatus(ChunkStatus.EMPTY);
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_TX_END,
                ChunkTraceSeverity.INFO,
                resolved.reason(),
                SOURCE + "#chunkis$restoreChunkDelta",
                resetProtoChunkToEmpty
                        ? "attached delta to proto chunk and reset status to EMPTY"
                        : usePersistedBaseChunkForBlocks
                          ? "attached delta to proto chunk and kept persisted base baseline"
                                : "attached authoritative full CIS baseline to proto chunk",
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(pos),
                null,
                operationId,
                delta.isDirty(),
                null
        );
    }

    @Unique
    private static boolean chunkis$shouldResetProtoChunkToEmpty(final Object chunkMetadata) {
        return !CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(chunkMetadata)
                && !CisNbtUtil.hasFullBlockBaseline(chunkMetadata);
    }

    /**
     * Traces state information on base NBT availability after NBT decode.
     *
     * @param world       server world context
     * @param pos         chunk position
     * @param delta       chunk delta state
     * @param operationId active load session operation ID
     */
    @Unique
    private static void chunkis$traceBaseMetadataAfterDecode(
            final ServerWorld world,
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId
    ) {
        if (!ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO)) {
            return;
        }
        final NbtCompound metadata = delta.getChunkMetadata();
        final boolean hasBase = CisNbtUtil.hasPersistedBaseChunkNbt(metadata);
        final boolean shouldUseBase = CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(metadata);
        final boolean fullBaseline = CisNbtUtil.hasFullBlockBaseline(metadata);
        final byte[] baseChunkPayload = metadata != null
                ? metadata.getByteArray(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY)
                  .orElse(new byte[0])
                : new byte[0];
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                hasBase
                        ? ChunkTraceEventType.BASE_METADATA_PRESENT_AFTER_DECODE
                        : ChunkTraceEventType.BASE_METADATA_MISSING_AFTER_DECODE,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SOURCE + "#chunkis$traceBaseMetadataAfterDecode",
                "decoded delta metadata: "
                        + io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard.describeLifecycleState(
                        delta)
                        + ", hasPersistedBaseChunk=" + hasBase
                        + ", shouldUsePersistedBaseChunk=" + shouldUseBase
                        + ", fullBlockBaseline=" + fullBaseline
                        + ", metadataKeys=" + (metadata != null ? metadata.getKeys() : java.util.List.of())
                        + ", baseChunkPayloadBytes=" + baseChunkPayload.length,
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(pos),
                null,
                operationId,
                delta.isDirty(),
                null
        );
        PayloadWatchTracer.traceChunkWatchContext(
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                pos,
                operationId,
                "decoded-delta-metadata",
                SOURCE + "#chunkis$traceBaseMetadataAfterDecode",
                "decoded delta metadata: "
                        + io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard.describeLifecycleState(
                        delta)
                        + ", hasPersistedBaseChunk=" + hasBase
                        + ", shouldUsePersistedBaseChunk=" + shouldUseBase
                        + ", fullBlockBaseline=" + fullBaseline
                        + ", metadataKeys=" + (metadata != null ? metadata.getKeys() : java.util.List.of())
                        + ", baseChunkPayloadBytes=" + baseChunkPayload.length
        );
    }

    /**
     * Loads a delta using a memory-first, disk-fallback strategy.
     *
     * <p>The global tracker is checked first because it may hold a newer in-memory
     * state than the on-disk copy. If the tracker has nothing (or only an empty
     * delta), CIS storage is used for cold loads.</p>
     *
     * @param world       the server world
     * @param pos         the chunk position
     * @param operationId active load session operation ID
     * @return the loaded delta, or {@code null} if none exists
     */
    @Unique
    private static ResolvedDelta chunkis$loadDelta(
            final ServerWorld world,
            final ChunkPos pos,
            final String operationId) {
        final ChunkDelta<?, ?> memoryDelta = GlobalChunkTracker.getDelta(world, pos);
        if (!chunkis$isDeltaAbsent(memoryDelta)) {
            return new ResolvedDelta(
                    chunkis$castBlockDelta(memoryDelta),
                    ChunkTraceReason.TRACKER_MEMORY
            );
        }
        final ChunkDelta<BlockState, NbtCompound> diskDelta = chunkis$loadDeltaFromDisk(world, pos, operationId);
        return new ResolvedDelta(
                diskDelta,
                chunkis$isDeltaAbsent(diskDelta)
                        ? ChunkTraceReason.NEITHER
                        : ChunkTraceReason.CHUNKIS_STORAGE
        );
    }

    /**
     * Loads a delta from persistent CIS storage.
     *
     * <p>{@link CisStorage#load(CisChunkPos, String)} returns an empty delta when no entry
     * exists; callers should apply {@link #chunkis$isDeltaAbsent} afterward.</p>
     *
     * @param world       the server world
     * @param pos         the chunk position
     * @param operationId active load session operation ID
     * @return the loaded disk delta (may be empty)
     */
    @Unique
    private static ChunkDelta<BlockState, NbtCompound> chunkis$loadDeltaFromDisk(
            final ServerWorld world,
            final ChunkPos pos,
            final String operationId) {
        final var storage = FabricCisStorageHelper.getStorage(world);
        final var cisPos = FabricCisStorageHelper.toStoragePos(pos);
        final var loaded = storage.loadWithPresence(cisPos, operationId);
        PayloadWatchTracer.traceDecodeOutcome(
                world,
                pos,
                loaded.delta(),
                operationId,
                loaded.storageEntryPresent()
        );
        return loaded.delta();
    }

    /**
     * Attaches {@code delta} to {@code chunk} through {@link ChunkisDeltaDuck}.
     *
     * <p>Stays defensive: if the interface is unexpectedly absent the vanilla
     * conversion path is not broken.</p>
     *
     * @param chunk                    the proto chunk to mutate
     * @param delta                    the delta to attach
     * @param worldId                  target world dimension registry ID string
     * @param operationId              active load session operation ID
     * @param restoreLoadedFromStorage true if delta was cold-loaded from storage
     */
    @Unique
    private static void chunkis$attachDeltaToChunk(
            final ProtoChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String worldId,
            final String operationId,
            final boolean restoreLoadedFromStorage) {
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            deltaDuck.chunkis$setDelta(delta);
            deltaDuck.chunkis$setRestoreOperationId(operationId);
            deltaDuck.chunkis$setRestoreLoadedFromStorage(restoreLoadedFromStorage);
            PayloadWatchTracer.traceProtoDeltaAttached(
                    worldId,
                    chunk,
                    delta,
                    operationId,
                    SOURCE + "#chunkis$attachDeltaToChunk"
            );
        }
    }

    /**
     * Restores state values into a wrapped WorldChunk if it is already materialized.
     *
     * @param world                    server world context
     * @param chunk                    proto chunk instance
     * @param protoDelta               proto delta instance
     * @param operationId              active load session operation ID
     * @param restoreLoadedFromStorage true if delta loaded from storage
     * @return true if wrapped world chunk resolved and restored
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static boolean chunkis$restoreWrappedFullChunk(
            final ServerWorld world,
            final ProtoChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final String operationId,
            final boolean restoreLoadedFromStorage
    ) {
        final WorldChunk wrappedChunk = chunkis$resolveWrappedWorldChunk(chunk);
        if (wrappedChunk == null) {
            return false;
        }

        final ChunkDelta<BlockState, NbtCompound> runtimeDelta;
        final ChunkisDeltaDuck wrappedDuck = (ChunkisDeltaDuck) wrappedChunk;
        final ChunkDelta<?, ?> existingDelta = wrappedDuck.chunkis$getDelta();
        if (existingDelta instanceof ChunkDelta<?, ?> typedExisting) {
            runtimeDelta = (ChunkDelta<BlockState, NbtCompound>) typedExisting;
        } else {
            runtimeDelta = new ChunkDelta<>(BlockState::isAir);
            wrappedDuck.chunkis$setDelta(runtimeDelta);
        }

        if (!ChunkDeltaOwnership.hasChunkisOwnedState(runtimeDelta)) {
            runtimeDelta.claimOwnership(
                    ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE.name(),
                    SOURCE + "#chunkis$restoreWrappedFullChunk"
            );
        }

        wrappedDuck.chunkis$setRestoreOperationId(operationId);
        wrappedDuck.chunkis$setRestoreLoadedFromStorage(restoreLoadedFromStorage);
        runtimeDelta.setSuppressInitialRepopulation(protoDelta.shouldSuppressInitialRepopulation());
        runtimeDelta.setChunkMetadata(protoDelta.getChunkMetadata(), false);
        PayloadWatchTracer.traceWorldChunkDeltaAttached(
                wrappedChunk,
                runtimeDelta,
                operationId,
                SOURCE + "#chunkis$restoreWrappedFullChunk"
        );
        ChunkRestorer.restore(world, wrappedChunk, protoDelta, runtimeDelta, operationId);
        chunkis$schedulePendingEntityReplay(world, wrappedChunk, runtimeDelta);
        PayloadWatchTracer.traceLiveChunkState(
                wrappedChunk,
                ChunkTraceEventType.WATCH_PRESENT_AFTER_RESTORE,
                "restore-live",
                SOURCE + "#chunkis$restoreWrappedFullChunk",
                operationId,
                protoDelta
        );
        PayloadWatchTracer.traceLiveChunkState(
                wrappedChunk,
                ChunkTraceEventType.WATCH_LIVE_CHUNK_STATE_AFTER_RESTORE,
                "after-restore-live-chunk",
                SOURCE + "#chunkis$restoreWrappedFullChunk",
                operationId,
                protoDelta
        );
        wrappedDuck.chunkis$setRestoreOperationId(null);
        if (world.getServer() != null) {
            world.getServer()
                    .execute(() -> ChunkRestorer.replayPendingEntitiesIfNeeded(
                            world,
                            wrappedChunk,
                            runtimeDelta,
                            operationId
                    ));
        }
        if (restoreLoadedFromStorage || !protoDelta.isDirty()) {
            protoDelta.markSaved();
        }
        return true;
    }

    /**
     * Schedules the entity replay logic queue for pending loaded entities.
     *
     * @param world        server world context
     * @param chunk        target world chunk
     * @param runtimeDelta target runtime delta mapping updates
     */
    @Unique
    private static void chunkis$schedulePendingEntityReplay(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta
    ) {
        if (runtimeDelta == null || runtimeDelta.countPendingEntities() == 0) {
            return;
        }
        runtimeDelta.forEachPendingEntity(entityNbt -> entityNbt.getIntArray("UUID")
                .map(Uuids::toUuid)
                .ifPresent(uuid -> ScheduledEntityReplayQueue.schedule(world,
                        chunk.getPos(),
                        uuid.toString(),
                        entityNbt)));
    }

    /**
     * Resolves the wrapped WorldChunk from full chunk loads.
     *
     * @param chunk proto chunk to query
     * @return wrapped WorldChunk if this is a full-chunk wrapper, or null
     */
    @Unique
    private static WorldChunk chunkis$resolveWrappedWorldChunk(final ProtoChunk chunk) {
        return chunk instanceof WrapperProtoChunk wrapper ? wrapper.getWrappedChunk() : null;
    }

    /**
     * Returns {@code true} when {@code delta} is absent or has no restorable Chunkis state.
     *
     * <p>Base-backed chunks may legitimately carry zero sparse block edits while
     * still remaining restorable via persisted base metadata. Treating
     * {@link ChunkDelta#isEmpty()} as "absent" drops those chunks on reload.</p>
     *
     * @param delta the delta to inspect; may be {@code null}
     * @return {@code true} if the delta should be ignored
     */
    @Unique
    private static boolean chunkis$isDeltaAbsent(final ChunkDelta<?, ?> delta) {
        return delta == null || !ChunkDeltaOwnership.hasRestorableChunkisState(delta);
    }

    /**
     * Casts a wildcard tracker delta to the concrete Minecraft block/NBT shape.
     *
     * <p>The global tracker stores deltas with wildcard generic types because it is
     * shared infrastructure. This mixin works exclusively with
     * {@code ChunkDelta<BlockState, NbtCompound>}, so the unchecked cast is
     * isolated here rather than scattered across the load path.</p>
     *
     * @param delta a wildcard delta from the tracker; must be a block/NBT delta
     * @return the same instance typed as {@code ChunkDelta<BlockState, NbtCompound>}
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> chunkis$castBlockDelta(
            final ChunkDelta<?, ?> delta) {
        return (ChunkDelta<BlockState, NbtCompound>) delta;
    }

    /**
     * Intercepts vanilla serialized chunk conversion and restores Chunkis state.
     *
     * <p>{@code poiStorage} and {@code key} are unused by Chunkis but are required
     * by the injection signature to match the target method exactly.</p>
     *
     * @param world      server world context
     * @param poiStorage point of interest storage (unused by Chunkis)
     * @param key        storage key (unused by Chunkis)
     * @param chunkPos   chunk position being converted
     * @param cir        callback holding the converted proto chunk
     */
    @Inject(method = "convert", at = @At("RETURN"))
    private void chunkis$onConvert(
            final ServerWorld world,
            final PointOfInterestStorage poiStorage,
            final StorageKey key,
            final ChunkPos chunkPos,
            final CallbackInfoReturnable<ProtoChunk> cir) {
        final ProtoChunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }
        final boolean syntheticChunkisLoad =
                chunkis$consumeSyntheticLoadMarker((SerializedChunk) (Object) this);
        if (!syntheticChunkisLoad) {
            return;
        }
        final String operationId = ChunkTraceStore.nextOperationId("load");
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SOURCE + "#chunkis$onConvert",
                "starting proto chunk delta restore",
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                DebugChunkKeys.of(chunkPos),
                null,
                operationId,
                null,
                null
        );
        chunkis$restoreChunkDelta(world, chunkPos, chunk, operationId);
    }

    /**
     * Immutable storage resolving delta status and loading reasons.
     *
     * @param delta  loaded block delta
     * @param reason reason code mapping load source
     */
    private record ResolvedDelta(
            ChunkDelta<BlockState, NbtCompound> delta,
            ChunkTraceReason reason
    ) {

    }
}
