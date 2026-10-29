package io.liparakis.chunkis.world.restoration.core;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
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
import io.liparakis.chunkis.world.tracking.ownership.OwnedDeltaAccess;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.poi.PointOfInterestTypes;

/**
 * Applies a proto chunk's CIS snapshot when vanilla promotes it to a live
 * {@link WorldChunk}, then resyncs portal POIs and the portal index.
 *
 * <p>Plain logic extracted from the world-chunk mixin, whose constructor hook only
 * delegates here inside its restore tracking scope.</p>
 */
public final class WorldChunkPromotion {

    /**
     * Trace source label for the restore path.
     */
    private static final String RESTORE_SOURCE = "WorldChunkPromotion#restoreChunkFromDelta";

    /**
     * Matches Nether portal POI entries already known to vanilla's POI storage.
     */
    private static final Predicate<RegistryEntry<PointOfInterestType>> PORTAL_POI_PREDICATE =
            type -> type.matchesKey(PointOfInterestTypes.NETHER_PORTAL);

    /**
     * Matches Nether portal blocks inside restored chunk sections.
     */
    private static final Predicate<BlockState> NETHER_PORTAL_BLOCK_PREDICATE =
            state -> state.isOf(Blocks.NETHER_PORTAL);

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private WorldChunkPromotion() {
        throw new AssertionError("Utility class");
    }

    /**
     * Extracts the CIS delta previously attached to a proto chunk during deserialization.
     *
     * @param proto proto chunk to query
     * @return resolved block delta, or null
     */
    @SuppressWarnings("unchecked")
    public static ChunkDelta<BlockState, NbtCompound> resolveProtoDelta(final ProtoChunk proto) {
        return proto instanceof ChunkisDeltaDuck duck ?
                (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta() : null;
    }

    /**
     * Extracts the restore operation ID mapping session details.
     *
     * @param deltaDuck duck mapping instance
     * @return resolved operation ID string
     */
    private static String takeRestoreOperationId(final ChunkisDeltaDuck deltaDuck) {
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
    static boolean shouldMarkRestoredDeltaSaved(
            final ChunkisDeltaDuck deltaDuck,
            final ChunkDelta<?, ?> delta
    ) {
        return deltaDuck.chunkis$wasRestoreLoadedFromStorage() || !delta.isDirty();
    }


    /**
     * Traces session log details mapping failed restore execution paths.
     *
     * @param worldId     target world registry ID string
     * @param chunkKey    key mapping coordinates
     * @param operationId active load/save operation ID
     * @param failedStage failed stage description string
     */
    private static void tracePostRestoreFailure(
            final String worldId, final DebugChunkKey chunkKey,
            final String operationId, final String failedStage) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.RESTORE_FAILED,
                ChunkTraceSeverity.ERROR, ChunkTraceReason.RESTORE_EXCEPTION, RESTORE_SOURCE, "post-restore follow-up" +
                        " failed during " + failedStage, worldId, chunkKey, null, operationId, null, null
        );
    }


    /**
     * Restores a promoted world chunk from the proto-carried CIS snapshot.
     *
     * @param world      server world context
     * @param chunk      target world chunk being populated
     * @param proto      source proto chunk instance
     * @param protoDelta source proto delta containing snapshot NBT
     */
    public static void restoreChunkFromDelta(
            final ServerWorld world, final WorldChunk chunk,
            final ProtoChunk proto,
            final ChunkDelta<BlockState, NbtCompound> protoDelta) {
        final ChunkDelta<BlockState, NbtCompound> selfDelta =
                OwnedDeltaAccess.getOrClaim(
                        chunk, ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE,
                        RESTORE_SOURCE
                );
        final String operationId = takeRestoreOperationId((ChunkisDeltaDuck) proto);
        PayloadWatchTracer.traceWorldChunkDeltaAttached(chunk, selfDelta, operationId, RESTORE_SOURCE);
        boolean coreRestoreCompleted = false;
        String failedStage = "chunk-restore";
        selfDelta.setSuppressInitialRepopulation(protoDelta.shouldSuppressInitialRepopulation());
        selfDelta.setChunkMetadata(protoDelta.getChunkMetadata(), false);
        traceLiveDeltaMetadata(world, chunk, protoDelta, selfDelta, operationId);
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
            resyncPortalPointOfInterestStorage(world, chunk);
            failedStage = "portal-index-update";
            io.liparakis.chunkis.portal.PortalChunkIndexManager.updateChunk(world, chunk);
        } catch (final Exception e) {
            if (coreRestoreCompleted) {
                tracePostRestoreFailure(
                        world.getRegistryKey()
                                .getValue()
                                .toString(),
                        DebugChunkKeys.of(chunk.getPos()), operationId, failedStage
                );
            }
            if (!coreRestoreCompleted) {
                // The live chunk does not reflect the stored entry; never let it overwrite storage.
                FabricCisStorageHelper.getStorage(world)
                        .protectFromOverwrite(FabricCisStorageHelper.toStoragePos(proto.getPos()));
            }
            Chunkis.LOGGER.error("Chunkis: Failed to restore chunk {} at stage {}; stored entry is write-protected "
                    + "for this session: {}", proto.getPos(), failedStage, !coreRestoreCompleted, e);
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

        if (shouldMarkRestoredDeltaSaved((ChunkisDeltaDuck) proto, protoDelta)) {
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
    private static void traceLiveDeltaMetadata(
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
    private static void resyncPortalPointOfInterestStorage(final ServerWorld world, final WorldChunk chunk) {
        final PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        final RegistryEntry<PointOfInterestType> portalType =
                world.getRegistryManager()
                        .getOrThrow(RegistryKeys.POINT_OF_INTEREST_TYPE)
                        .getOrThrow(PointOfInterestTypes.NETHER_PORTAL);

        final int blockCount = addPortalPois(chunk, poiStorage, portalType);
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
    private static int addPortalPois(
            final WorldChunk chunk, final PointOfInterestStorage poiStorage,
            final RegistryEntry<PointOfInterestType> portalType) {
        final int[] count = {0};
        chunk.forEachBlockMatchingPredicate(
                NETHER_PORTAL_BLOCK_PREDICATE, (pos, state) -> {
                    count[0]++;
                    poiStorage.add(pos, portalType);
                }
        );
        return count[0];
    }

}
