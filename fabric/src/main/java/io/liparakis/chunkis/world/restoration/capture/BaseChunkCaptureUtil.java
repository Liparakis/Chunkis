package io.liparakis.chunkis.world.restoration.capture;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Stateless helper for capturing a persisted, vanilla-compatible base chunk snapshot into a
 * {@link ChunkDelta}.
 *
 * <p>A "base chunk" is the serialized vanilla state of a chunk at first-capture
 * time. It is stored in the delta's metadata so that subsequent modifications can be diffed against
 * a stable baseline rather than the live world state.</p>
 *
 * <p>Capture is idempotent: every public entry point delegates to
 * {@link #shouldSkipCapture} and exits immediately when a base chunk has already been recorded,
 * preventing the original baseline from being overwritten.</p>
 */
public final class BaseChunkCaptureUtil {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private BaseChunkCaptureUtil() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures the base chunk if missing without forcing an immediate synchronous storage write.
     *
     * <p>Use this on normal live-mutation hooks where blocking the server thread on first touch is
     * worse than letting the existing dirty-delta save pipeline flush the captured base snapshot.</p>
     *
     * @param world the server world that owns the chunk
     * @param chunk the live chunk being captured
     * @param delta the delta attached to that chunk
     */
    public static void captureBaseChunkIfMissing(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta
    ) {
        if (shouldSkipCapture(chunk, delta)) {
            traceCaptureSkipped(world, chunk, delta, "captureBaseChunkIfMissing");
            return;
        }

        traceLifecycle(
                world,
                chunk,
                delta,
                ChunkTraceEventType.BASE_CAPTURE_REQUESTED,
                "BaseChunkCaptureUtil#captureBaseChunkIfMissing",
                "base capture requested"
        );
        captureBaseChunk(world, chunk, delta);
    }

    /**
     * Captures a serialized base chunk into {@code delta} if one has not already been recorded,
     * detecting portal presence automatically.
     *
     * <p>Prefer {@link #captureBaseChunk(ServerWorld, WorldChunk, ChunkDelta, boolean)}
     * when portal presence has already been determined externally to avoid a redundant full-chunk
     * block scan.</p>
     *
     * @param world the server world the chunk belongs to
     * @param chunk the live chunk to snapshot
     * @param delta the delta to update with the base chunk snapshot
     * @return {@code delta}, updated in-place if capture ran; unmodified otherwise
     */
    @SuppressWarnings("UnusedReturnValue")
    public static ChunkDelta<BlockState, NbtCompound> captureBaseChunk(final ServerWorld world,
            final WorldChunk chunk, final ChunkDelta<BlockState, NbtCompound> delta) {
        if (shouldSkipCapture(chunk, delta)) {
            traceCaptureSkipped(world, chunk, delta, "captureBaseChunk");
            return delta;
        }
        return captureBaseChunk(world, chunk, delta, hasPortalBlocks(chunk));
    }

    /**
     * Captures a serialized base chunk into {@code delta} if one has not already been recorded, with
     * an explicit portal flag.
     *
     * @param world       the server world the chunk belongs to
     * @param chunk       the live chunk to snapshot
     * @param delta       the delta to update with the base chunk snapshot
     * @param portalChunk {@code true} if the chunk contains nether portal blocks; pass the result of
     *                    {@link #hasPortalBlocks} when already known
     * @return {@code delta}, updated in-place if capture ran; unmodified otherwise
     */
    public static ChunkDelta<BlockState, NbtCompound> captureBaseChunk(final ServerWorld world,
            final WorldChunk chunk, final ChunkDelta<BlockState, NbtCompound> delta,
            final boolean portalChunk) {
        if (shouldSkipCapture(chunk, delta)) {
            traceCaptureSkipped(world, chunk, delta, "captureBaseChunk(portal)");
            return delta;
        }

        traceLifecycle(world, chunk, delta, ChunkTraceEventType.BASE_CAPTURE_STARTED,
                "BaseChunkCaptureUtil#captureBaseChunk", "base capture started");
        final boolean debugEnabled =
                ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO);
        final int beforeBlocks = debugEnabled ? delta.getBlockChangesCount() : 0;
        final int beforeBlockEntities = debugEnabled ? delta.getBlockEntities()
                                                       .size() : 0;
        final DebugChunkKey chunkKey = debugEnabled ? DebugChunkKeys.of(chunk.getPos()) : null;
        if (debugEnabled) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.BASE_NBT_CAPTURE_STARTED, ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE, "BaseChunkCaptureUtil#captureBaseChunk",
                    "starting base capture: " + DeltaPersistenceGuard.describeDeltaShape(delta),
                    world.getRegistryKey()
                            .getValue()
                            .toString(), chunkKey, null, null, delta.isDirty(), null);
        }
        PayloadWatchTracer.traceLiveChunkState(chunk,
                ChunkTraceEventType.WATCH_LIVE_CHUNK_STATE_BEFORE_BASE_CAPTURE, "before-base-capture",
                "BaseChunkCaptureUtil#captureBaseChunk", null, delta);
        PayloadWatchTracer.traceDeltaStage(world.getRegistryKey()
                        .getValue()
                        .toString(), chunk.getPos(),
                delta, null, ChunkTraceEventType.WATCH_CAPTURED, "base-capture-before-clear",
                "BaseChunkCaptureUtil#captureBaseChunk", "delta state before base capture clear",
                null);

        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                CisNbtUtil.extractPersistedStructureMetadata(delta.getChunkMetadata()), true, false,
                SerializedChunk.fromChunk(world, chunk)
                        .serialize(), portalChunk);
        CisNbtUtil.preserveMigratedAuthoritativeMetadata(delta.getChunkMetadata(), metadata);

        delta.setChunkMetadata(metadata);
        traceLifecycle(world, chunk, delta, ChunkTraceEventType.BASE_METADATA_ATTACHED,
                "BaseChunkCaptureUtil#captureBaseChunk", "base metadata attached");
        delta.clearBlockPayloads(false);
        PayloadWatchTracer.traceDeltaStage(world.getRegistryKey()
                        .getValue()
                        .toString(), chunk.getPos(),
                delta, null, ChunkTraceEventType.WATCH_CAPTURED, "base-capture-after-clear",
                "BaseChunkCaptureUtil#captureBaseChunk", "delta state after base capture clear", null);
        delta.setSuppressInitialRepopulation(true);
        traceLifecycle(world, chunk, delta, ChunkTraceEventType.BASE_CAPTURE_COMPLETED,
                "BaseChunkCaptureUtil#captureBaseChunk", "base capture completed");

        if (debugEnabled) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.BASE_NBT_CAPTURED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    "BaseChunkCaptureUtil#captureBaseChunk",
                    "captured base NBT: metadataKeys=" + metadata.getKeys()
                            + ", suppressInitialRepopulation=" + delta.shouldSuppressInitialRepopulation()
                            + ", blocksBefore=" + beforeBlocks + ", blocksAfter="
                            + delta.getBlockChangesCount() + ", blockEntitiesBefore=" + beforeBlockEntities
                            + ", blockEntitiesAfter="
                            + delta.getBlockEntities()
                            .size(),
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    chunkKey,
                    null,
                    null,
                    delta.isDirty(),
                    null);
        }

        return delta;
    }

    /**
     * Returns {@code true} if {@code chunk} contains at least one nether portal block.
     *
     * <p>Iterates all blocks in the chunk and short-circuits on the first match.
     * The result should be cached by the caller if it will be checked more than once for the same
     * chunk, as this triggers a full chunk scan.</p>
     *
     * @param chunk the chunk to scan; {@code null} returns {@code false}
     * @return {@code true} if a {@link Blocks#NETHER_PORTAL} block is present
     */
    public static boolean hasPortalBlocks(final WorldChunk chunk) {
        if (chunk == null) {
            return false;
        }
        final AtomicBoolean found = new AtomicBoolean();
        chunk.forEachBlockMatchingPredicate(state -> state.isOf(Blocks.NETHER_PORTAL),
                (pos, state) -> found.set(true));
        return found.get();
    }

    /**
     * Returns {@code true} when base chunk capture should be skipped.
     *
     * <p>Skipped when either argument is {@code null}, or when a base chunk NBT
     * has already been persisted in the delta's metadata. The latter prevents the original baseline
     * from being overwritten on subsequent saves.</p>
     *
     * @param chunk the candidate chunk
     * @param delta the candidate delta
     * @return {@code true} if capture should not proceed
     */
    private static boolean shouldSkipCapture(final WorldChunk chunk, final ChunkDelta<?, ?> delta) {
        return chunk == null
                || delta == null
                || CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata())
                || CisNbtUtil.hasFullBlockBaseline(delta.getChunkMetadata());
    }

    /**
     * Logs trace information when base capture is skipped.
     *
     * @param world  server world reference
     * @param chunk  target world chunk
     * @param delta  associated block delta
     * @param source caller label used in trace output
     */
    private static void traceCaptureSkipped(final ServerWorld world, final WorldChunk chunk,
            final ChunkDelta<?, ?> delta, final String source) {
        if (world == null || chunk == null || delta == null
                || !ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO)) {
            return;
        }
        ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.BASE_NBT_CAPTURE_SKIPPED, ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE, "BaseChunkCaptureUtil#" + source,
                "skipped base capture: " + DeltaPersistenceGuard.describeDeltaShape(delta),
                world.getRegistryKey()
                        .getValue()
                        .toString(), DebugChunkKeys.of(chunk.getPos()), null, null,
                delta.isDirty(), null);
    }

    /**
     * Logs lifecycle transitions for debugging traces.
     *
     * @param world     server world reference
     * @param chunk     target world chunk
     * @param delta     associated block delta
     * @param eventType lifecycle event type mapping
     * @param source    caller label used in trace output
     * @param message   description detail text
     */
    private static void traceLifecycle(final ServerWorld world, final WorldChunk chunk,
            final ChunkDelta<?, ?> delta, final ChunkTraceEventType eventType, final String source,
            final String message) {
        if (world == null || chunk == null || delta == null
                || !ChunkisDebugConfig.allows(ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceSeverity.INFO)) {
            return;
        }
        ChunkTraceStore.trace(ChunkisDebugDomain.CHUNK_LIFECYCLE, eventType, ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE, source,
                message + ": " + DeltaPersistenceGuard.describeLifecycleState(delta),
                world.getRegistryKey()
                        .getValue()
                        .toString(), DebugChunkKeys.of(chunk.getPos()), null, null,
                delta.isDirty(), null);
    }
}
