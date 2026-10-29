package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Decides whether a delta may be persisted, recovering or rejecting sparse deltas
 * that lack a base anchor.
 *
 * <p>A sparse delta without a persisted base or full baseline would be replayed
 * over regenerated terrain on load, so it is either upgraded with a captured base
 * from the live chunk or rejected with a trace. Extracted from the save path so
 * {@link ChunkSaveCoordinator} only orchestrates capture and persistence.</p>
 */
final class SaveDeltaGuard {

    /**
     * Trace source identification tag label for rejection path.
     */
    private static final String REJECT_SOURCE = "SaveDeltaGuard#rejectSparse";

    /**
     * World whose deltas this guard checks.
     */
    private final ServerWorld world;

    /**
     * Creates a guard for {@code world}.
     *
     * @param world owning world
     */
    SaveDeltaGuard(final ServerWorld world) {
        this.world = world;
    }

    /**
     * Runs the non-persistence preflight checks for async save queue submission.
     *
     * <p>This covers the policy decisions specific to queueing: skip clean deltas,
     * reject unowned deltas with assertion tracing, and emit the queue-requested
     * lifecycle trace for owned dirty deltas.</p>
     *
     * @param pos         chunk position being queued
     * @param delta       candidate delta
     * @param operationId trace correlation ID
     * @return delta ready for persistence preparation, or {@code null} if queueing
     *         should stop
     */
    ChunkDelta<BlockState, NbtCompound> prepareDeltaForQueueing(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId
    ) {
        if (delta == null || !delta.isDirty()) {
            if (delta != null) {
                PayloadWatchTracer.traceDeltaStage(
                        world.getRegistryKey()
                                .getValue()
                                .toString(),
                        pos,
                        delta,
                        operationId,
                        ChunkTraceEventType.WATCH_SKIPPED,
                        "save-not-queued-clean",
                        "ChunkSaveCoordinator#queueDirtyDelta",
                        "delta was clean when queueing was attempted; async save not queued",
                        null
                );
            }
            return null;
        }
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            PayloadWatchTracer.traceDeltaStage(
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    pos,
                    delta,
                    operationId,
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "save-bypassed-without-ownership",
                    "ChunkSaveCoordinator#queueDirtyDelta",
                    "chunk was not Chunkis-owned; async save not queued",
                    null
            );
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.SAVE_WITHOUT_OWNERSHIP,
                    "ChunkSaveCoordinator#queueDirtyDelta",
                    "attempted to queue Chunkis save without ownership",
                    worldId(),
                    debugChunkKey(pos),
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
                    "ChunkSaveCoordinator#queueDirtyDelta",
                    delta,
                    null
            );
            return null;
        }

        ChunkOwnershipTraceHelper.traceDecision(
                world.getRegistryKey(),
                pos,
                "CLAIMED",
                ChunkTraceReason.valueOf(delta.getOwnershipReason()),
                "ChunkSaveCoordinator#queueDirtyDelta",
                delta,
                null
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_QUEUE_REQUESTED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "ChunkSaveCoordinator#queueDirtyDelta",
                "save queue requested: " + DeltaPersistenceGuard.describeLifecycleState(delta),
                worldId(),
                debugChunkKey(pos),
                null,
                operationId,
                delta.isDirty(),
                null
        );
        return delta;
    }

    /**
     * Runs the shared persistence guard pipeline used by both synchronous saves
     * and async save-queue submissions.
     *
     * <p>The pipeline traces base metadata, attempts base-snapshot recovery for
     * sparse payloads, and rejects the delta if the sparse persistence guard still
     * fails afterward.</p>
     *
     * @param pos         chunk position being persisted
     * @param delta       candidate delta
     * @param path        persistence path label
     * @param caller      caller label for tracing
     * @param operationId trace correlation ID
     * @return prepared delta, or {@code null} if persistence should stop
     */
    ChunkDelta<BlockState, NbtCompound> prepareDeltaForPersistence(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String path,
            final String caller,
            final String operationId,
            final Chunk chunk
    ) {
        // A released block payload is written with its blocks from copied containers or from the stored entry
        // (DeferredBlocksSnapshotView, StoredBlocksSnapshotView), never from the delta's instructions alone.
        final ChunkDelta<BlockState, NbtCompound> recoveredDelta = recoverSparseDeltaOnSaveGuard(
                pos,
                delta,
                caller,
                operationId,
                chunk
        );
        return rejectSparse(pos, recoveredDelta, path, caller, operationId)
                ? null
                : recoveredDelta;
    }

    /**
     * Recovers sparse delta shapes attempting to snapshot base chunk bounds on save failures.
     *
     * @param pos         chunk coordinates pos
     * @param delta       block delta state
     * @param caller      caller class/method label
     * @param operationId active load/save operation ID
     * @return recovered block delta
     */
    private ChunkDelta<BlockState, NbtCompound> recoverSparseDeltaOnSaveGuard(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String caller,
            final String operationId,
            final Chunk chunk
    ) {
        traceBaseMetadataBeforeSaveGuard(pos, delta, caller, operationId);
        if (!DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta, true)) {
            return delta;
        }

        final WorldChunk lookedUpChunk = LoadedChunks.getIfAccessible(world, pos.x, pos.z);
        final WorldChunk liveChunk = chooseLiveChunkForBaseRecovery(chunk, lookedUpChunk);
        final ChunkDelta<BlockState, NbtCompound> liveDelta = liveChunk != null
                ? getChunkDelta(liveChunk)
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
                worldId(),
                debugChunkKey(pos),
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
                worldId(),
                debugChunkKey(pos),
                null,
                operationId,
                recoveredDelta.isDirty(),
                null
        );
        return recoveredDelta;
    }

    /**
     * Traces base metadata details prior to running save guard validations.
     *
     * @param pos         chunk coordinates pos
     * @param delta       block delta state
     * @param caller      caller class/method label
     * @param operationId active load/save operation ID
     */
    private void traceBaseMetadataBeforeSaveGuard(
            final ChunkPos pos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String caller,
            final String operationId
    ) {
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
                        ? ChunkTraceEventType.BASE_METADATA_PRESENT_BEFORE_SAVE_GUARD
                        : ChunkTraceEventType.BASE_METADATA_MISSING_BEFORE_SAVE_GUARD,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                caller,
                "save guard precheck: " + DeltaPersistenceGuard.describeLifecycleState(delta)
                        + ", hasPersistedBaseChunk=" + hasBase
                        + ", shouldUsePersistedBaseChunk=" + shouldUseBase
                        + ", fullBlockBaseline=" + fullBaseline
                        + ", metadataKeys=" + (metadata != null ? metadata.getKeys() : List.of())
                        + ", baseChunkPayloadBytes=" + baseChunkPayload.length,
                worldId(),
                debugChunkKey(pos),
                null,
                operationId,
                delta.isDirty(),
                null
        );
        PayloadWatchTracer.traceChunkWatchContext(
                worldId(),
                pos,
                operationId,
                "save-guard-precheck",
                caller,
                "save guard precheck: " + DeltaPersistenceGuard.describeLifecycleState(delta)
                        + ", hasPersistedBaseChunk=" + hasBase
                        + ", shouldUsePersistedBaseChunk=" + shouldUseBase
                        + ", fullBlockBaseline=" + fullBaseline
                        + ", metadataKeys=" + (metadata != null ? metadata.getKeys() : List.of())
                        + ", baseChunkPayloadBytes=" + baseChunkPayload.length
        );
    }

    /**
     * Checks the sparse-delta guard and logs a rejection if triggered.
     *
     * <p>Shared by the queued saves and the shutdown sweep, which both persist through
     * {@link #prepareDeltaForPersistence}.</p>
     *
     * @param pos         the chunk position
     * @param delta       the delta under evaluation
     * @param path        save path label
     * @param caller      caller label
     * @param operationId active load/save operation ID
     * @return {@code true} if the save should be rejected
     */
    private boolean rejectSparse(
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta,
            final String path,
            final String caller,
            final String operationId) {
        if (DeltaPersistenceGuard.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta)) {
            if (ChunkisDebugConfig.allows(ChunkisDebugDomain.ASSERTIONS, ChunkTraceSeverity.ERROR)) {
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.ASSERTIONS,
                        ChunkTraceEventType.ASSERTION_FAILED,
                        ChunkTraceSeverity.ERROR,
                        ChunkTraceReason.INVALID_PAYLOAD,
                        caller,
                        "attempted to persist sparse block-entity payload without persisted base chunk NBT on "
                                + path + ": " + DeltaPersistenceGuard.describeDeltaShape(delta),
                        worldId(),
                        debugChunkKey(pos),
                        null,
                        operationId,
                        delta.isDirty(),
                        null
                );
            }
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
                worldId(),
                debugChunkKey(pos),
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
     * Returns the delta attached to {@code chunk}, or {@code null}.
     */
    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> getChunkDelta(final Chunk chunk) {
        return chunk instanceof ChunkisDeltaDuck duck
                ? (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta()
                : null;
    }

    /**
     * Returns the live chunk to use for base recovery: {@code chunk} when it is a
     * {@link WorldChunk}, otherwise the looked-up fallback.
     */
    private static WorldChunk chooseLiveChunkForBaseRecovery(final Chunk chunk, final WorldChunk lookedUpChunk) {
        return chunk instanceof WorldChunk ? (WorldChunk) chunk : lookedUpChunk;
    }

    /**
     * Returns the trace key for {@code pos}.
     */
    private static DebugChunkKey debugChunkKey(final ChunkPos pos) {
        return new DebugChunkKey(pos.x, pos.z);
    }

    /**
     * Returns the world registry id string used in traces.
     */
    private String worldId() {
        return world.getRegistryKey()
                .getValue()
                .toString();
    }
}
