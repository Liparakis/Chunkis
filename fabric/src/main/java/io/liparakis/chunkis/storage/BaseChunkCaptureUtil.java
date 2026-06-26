package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.storage.io.CisStorage;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stateless helper for capturing a persisted, vanilla-compatible base chunk
 * snapshot into a {@link ChunkDelta}.
 *
 * <p>A "base chunk" is the serialized vanilla state of a chunk at first-capture
 * time. It is stored in the delta's metadata so that subsequent modifications
 * can be diffed against a stable baseline rather than the live world state.</p>
 *
 * <p>Capture is idempotent: every public entry point delegates to
 * {@link #shouldSkipCapture} and exits immediately when a base chunk has
 * already been recorded, preventing the original baseline from being
 * overwritten.</p>
 *
 * @author Liparakis
 * @version 1.0
 *
 */
public final class BaseChunkCaptureUtil {


    /**
     * Utility class – not instantiable.
     */
    private BaseChunkCaptureUtil() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures the base chunk if missing and immediately persists the delta
     * synchronously on the calling thread.
     *
     * <p>Use this on paths where deferring to the async queue is unsafe — for
     * example, when a restored or newly-edited chunk may unload before vanilla's
     * next normal save pass.</p>
     *
     * <p>On success the storage marks the delta saved. On failure the captured
     * metadata is left dirty so subsequent save paths can retry.</p>
     *
     * @param world the server world that owns the chunk
     * @param chunk the live chunk being captured
     * @param delta the delta attached to that chunk
     */
    public static void captureAndPersistBaseChunkIfMissing(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        if (shouldSkipCapture(chunk, delta)) {
            traceCaptureSkipped(world, chunk, delta, "captureAndPersistBaseChunkIfMissing");
            return;
        }

        traceLifecycle(
                world,
                chunk,
                delta,
                ChunkTraceEventType.BASE_CAPTURE_REQUESTED,
                "BaseChunkCaptureUtil#captureAndPersistBaseChunkIfMissing",
                "base capture requested"
        );

        captureBaseChunk(world, chunk, delta);

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);
        storage.save(new CisChunkPos(chunk.getPos().x, chunk.getPos().z), delta);
    }

    /**
     * Captures a serialized base chunk into {@code delta} if one has not already
     * been recorded, detecting portal presence automatically.
     *
     * <p>Prefer {@link #captureBaseChunk(ServerWorld, WorldChunk, ChunkDelta, boolean)}
     * when portal presence has already been determined externally to avoid a
     * redundant full-chunk block scan.</p>
     *
     * @param world the server world the chunk belongs to
     * @param chunk the live chunk to snapshot
     * @param delta the delta to update with the base chunk snapshot
     * @return {@code delta}, updated in-place if capture ran; unmodified otherwise
     */
    public static ChunkDelta<BlockState, NbtCompound> captureBaseChunk(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        if (shouldSkipCapture(chunk, delta)) {
            traceCaptureSkipped(world, chunk, delta, "captureBaseChunk");
            return delta;
        }
        return captureBaseChunk(world, chunk, delta, hasPortalBlocks(chunk));
    }

    /**
     * Captures a serialized base chunk into {@code delta} if one has not already
     * been recorded, with an explicit portal flag.
     *
     * @param world       the server world the chunk belongs to
     * @param chunk       the live chunk to snapshot
     * @param delta       the delta to update with the base chunk snapshot
     * @param portalChunk {@code true} if the chunk contains nether portal blocks;
     *                    pass the result of {@link #hasPortalBlocks} when already known
     * @return {@code delta}, updated in-place if capture ran; unmodified otherwise
     */
    public static ChunkDelta<BlockState, NbtCompound> captureBaseChunk(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final boolean portalChunk) {
        if (shouldSkipCapture(chunk, delta)) {
            traceCaptureSkipped(world, chunk, delta, "captureBaseChunk(portal)");
            return delta;
        }

        final int beforeBlocks = delta.getBlockInstructions().size();
        final int beforeBlockEntities = delta.getBlockEntities().size();
        final DebugChunkKey chunkKey = new DebugChunkKey(chunk.getPos().x, chunk.getPos().z);
        traceLifecycle(
                world,
                chunk,
                delta,
                ChunkTraceEventType.BASE_CAPTURE_STARTED,
                "BaseChunkCaptureUtil#captureBaseChunk",
                "base capture started"
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.BASE_NBT_CAPTURE_STARTED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "BaseChunkCaptureUtil#captureBaseChunk",
                "starting base capture: " + DeltaPersistenceGuard.describeDeltaShape(delta),
                world.getRegistryKey().getValue().toString(),
                chunkKey,
                null,
                null,
                delta.isDirty(),
                null
        );

        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                CisNbtUtil.extractPersistedStructureMetadata(delta.getChunkMetadata()),
                true,
                false,
                SerializedChunk.fromChunk(world, chunk).serialize(),
                portalChunk
        );

        delta.setChunkMetadata(metadata);
        traceLifecycle(
                world,
                chunk,
                delta,
                ChunkTraceEventType.BASE_METADATA_ATTACHED,
                "BaseChunkCaptureUtil#captureBaseChunk",
                "base metadata attached"
        );
        delta.clearBlockPayloads(false);
        delta.setSuppressInitialRepopulation(true);
        traceLifecycle(
                world,
                chunk,
                delta,
                ChunkTraceEventType.BASE_CAPTURE_COMPLETED,
                "BaseChunkCaptureUtil#captureBaseChunk",
                "base capture completed"
        );

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.BASE_NBT_CAPTURED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "BaseChunkCaptureUtil#captureBaseChunk",
                "captured base NBT: metadataKeys=" + metadata.getKeys()
                        + ", suppressInitialRepopulation=" + delta.shouldSuppressInitialRepopulation()
                        + ", blocksBefore=" + beforeBlocks
                        + ", blocksAfter=" + delta.getBlockInstructions().size()
                        + ", blockEntitiesBefore=" + beforeBlockEntities
                        + ", blockEntitiesAfter=" + delta.getBlockEntities().size(),
                world.getRegistryKey().getValue().toString(),
                chunkKey,
                null,
                null,
                delta.isDirty(),
                null
        );

        return delta;
    }

    /**
     * Returns {@code true} if {@code chunk} contains at least one nether portal block.
     *
     * <p>Iterates all blocks in the chunk and short-circuits on the first match.
     * The result should be cached by the caller if it will be checked more than
     * once for the same chunk, as this triggers a full chunk scan.</p>
     *
     * @param chunk the chunk to scan; {@code null} returns {@code false}
     * @return {@code true} if a {@link Blocks#NETHER_PORTAL} block is present
     */
    public static boolean hasPortalBlocks(final WorldChunk chunk) {
        if (chunk == null) {
            return false;
        }
        final AtomicBoolean found = new AtomicBoolean();
        chunk.forEachBlockMatchingPredicate(
                state -> state.isOf(Blocks.NETHER_PORTAL),
                (pos, state) -> found.set(true)
        );
        return found.get();
    }

    /**
     * Returns {@code true} when base chunk capture should be skipped.
     *
     * <p>Skipped when either argument is {@code null}, or when a base chunk NBT
     * has already been persisted in the delta's metadata. The latter prevents
     * the original baseline from being overwritten on subsequent saves.</p>
     *
     * @param chunk the candidate chunk
     * @param delta the candidate delta
     * @return {@code true} if capture should not proceed
     */
    private static boolean shouldSkipCapture(
            final WorldChunk chunk,
            final ChunkDelta<?, ?> delta) {
        return chunk == null
                || delta == null
                || CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata());
    }

    private static void traceCaptureSkipped(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<?, ?> delta,
            final String source
    ) {
        if (world == null || chunk == null || delta == null) {
            return;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.BASE_NBT_CAPTURE_SKIPPED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "BaseChunkCaptureUtil#" + source,
                "skipped base capture: " + DeltaPersistenceGuard.describeDeltaShape(delta),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                null,
                null,
                delta.isDirty(),
                null
        );
    }

    private static void traceLifecycle(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<?, ?> delta,
            final ChunkTraceEventType eventType,
            final String source,
            final String message
    ) {
        if (world == null || chunk == null || delta == null) {
            return;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                eventType,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                source,
                message + ": " + DeltaPersistenceGuard.describeLifecycleState(delta),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                null,
                null,
                delta.isDirty(),
                null
        );
    }
}
