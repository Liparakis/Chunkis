package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.storage.CisNbtUtil;
import io.liparakis.chunkis.storage.FabricCisStorageHelper;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.GlobalChunkTracker;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.SerializedChunk;
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
 *
 * @author Liparakis
 * @version 1.2
 *
 */
@Mixin(SerializedChunk.class)
public class ChunkSerializerMixin {

    @Unique
    private static final String SOURCE = "ChunkSerializerMixin";


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
    private static void chunkis$onConvert(
            final ServerWorld world,
            final PointOfInterestStorage poiStorage,
            final StorageKey key,
            final ChunkPos chunkPos,
            final CallbackInfoReturnable<ProtoChunk> cir) {
        final ProtoChunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SOURCE + "#chunkis$onConvert",
                "starting proto chunk delta restore",
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                null,
                null,
                null
        );
        chunkis$restoreChunkDelta(world, chunkPos, chunk);
    }

    /**
     * Loads, attaches, and applies the Chunkis delta for a converted proto chunk.
     *
     * <p>If no meaningful delta exists the proto chunk is left exactly as vanilla
     * produced it.</p>
     *
     * <p>Restore order:</p>
     * <ol>
     *   <li>Load delta (memory → disk).</li>
     *   <li>Trace log.</li>
     *   <li>Set suppression flag from persisted metadata.</li>
     *   <li>Attach delta to chunk via {@link ChunkisDeltaDuck}.</li>
     *   <li>Reset status to {@link ChunkStatus#EMPTY} so vanilla worldgen regenerates
     *       terrain before snapshot replay.</li>
     * </ol>
     *
     * @param world the server world
     * @param pos   the chunk position
     * @param chunk the converted proto chunk
     */
    @Unique
    private static void chunkis$restoreChunkDelta(
            final ServerWorld world,
            final ChunkPos pos,
            final ProtoChunk chunk) {
        final ResolvedDelta resolved = chunkis$loadDelta(world, pos);

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_SOURCE_RESOLVED,
                ChunkTraceSeverity.INFO,
                resolved.reason(),
                SOURCE + "#chunkis$loadDelta",
                "load source resolved",
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                null,
                resolved.delta() != null && resolved.delta().isDirty(),
                null
        );

        if (chunkis$isDeltaAbsent(resolved.delta())) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.LOAD_TX_END,
                    ChunkTraceSeverity.INFO,
                    resolved.reason(),
                    SOURCE + "#chunkis$restoreChunkDelta",
                    "no meaningful delta to attach",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(pos.x, pos.z),
                    null,
                    null,
                    null,
                    null
            );
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta = resolved.delta();
        delta.setSuppressInitialRepopulation(CisNbtUtil.shouldSuppressInitialRepopulation(delta));

        chunkis$attachDeltaToChunk(chunk, delta);
        chunk.setStatus(ChunkStatus.EMPTY);
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.LOAD_TX_END,
                ChunkTraceSeverity.INFO,
                resolved.reason(),
                SOURCE + "#chunkis$restoreChunkDelta",
                "attached delta to proto chunk and reset status to EMPTY",
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(pos.x, pos.z),
                null,
                null,
                delta.isDirty(),
                null
        );
    }

    /**
     * Loads a delta using a memory-first, disk-fallback strategy.
     *
     * <p>The global tracker is checked first because it may hold a newer in-memory
     * state than the on-disk copy. If the tracker has nothing (or only an empty
     * delta), CIS storage is used for cold loads.</p>
     *
     * @param world the server world
     * @param pos   the chunk position
     * @return the loaded delta, or {@code null} if none exists
     */
    @Unique
    private static ResolvedDelta chunkis$loadDelta(
            final ServerWorld world,
            final ChunkPos pos) {
        final ChunkDelta<?, ?> memoryDelta = GlobalChunkTracker.getDelta(world, pos);
        if (!chunkis$isDeltaAbsent(memoryDelta)) {
            return new ResolvedDelta(
                    chunkis$castBlockDelta(memoryDelta),
                    ChunkTraceReason.TRACKER_MEMORY
            );
        }
        final ChunkDelta<BlockState, NbtCompound> diskDelta = chunkis$loadDeltaFromDisk(world, pos);
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
     * <p>{@link CisStorage#load(CisChunkPos)} returns an empty delta when no entry
     * exists; callers should apply {@link #chunkis$isDeltaAbsent} afterward.</p>
     *
     * @param world the server world
     * @param pos   the chunk position
     * @return the loaded disk delta (may be empty)
     */
    @Unique
    private static ChunkDelta<BlockState, NbtCompound> chunkis$loadDeltaFromDisk(
            final ServerWorld world,
            final ChunkPos pos) {
        return FabricCisStorageHelper.getStorage(world)
                .load(new CisChunkPos(pos.x, pos.z));
    }

    /**
     * Attaches {@code delta} to {@code chunk} through {@link ChunkisDeltaDuck}.
     *
     * <p>Stays defensive: if the interface is unexpectedly absent the vanilla
     * conversion path is not broken.</p>
     *
     * @param chunk the proto chunk to mutate
     * @param delta the delta to attach
     */
    @Unique
    private static void chunkis$attachDeltaToChunk(
            final ProtoChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            deltaDuck.chunkis$setDelta(delta);
        }
    }

    /**
     * Returns {@code true} when {@code delta} is absent or carries no payload.
     *
     * @param delta the delta to inspect; may be {@code null}
     * @return {@code true} if the delta should be ignored
     */
    @Unique
    private static boolean chunkis$isDeltaAbsent(final ChunkDelta<?, ?> delta) {
        return delta == null || delta.isEmpty();
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

    private record ResolvedDelta(
            ChunkDelta<BlockState, NbtCompound> delta,
            ChunkTraceReason reason
    ) {
    }

}
