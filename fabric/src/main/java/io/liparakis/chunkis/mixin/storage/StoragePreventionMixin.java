package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.scanner.NbtScanner;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionBasedStorage;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses all vanilla MCA region file I/O, routing chunk persistence
 * exclusively through the Chunkis CIS delta system.
 *
 * <p>
 * The following operations are intercepted and cancelled at {@code HEAD}
 * before any disk I/O occurs:
 * <ul>
 * <li>{@code write} — chunk NBT writes to {@code .mca} files</li>
 * <li>{@code getTagAt} — chunk NBT reads from {@code .mca} files; returns null,
 *     signalling "chunk not in storage" so the load pipeline falls through to
 *     {@code ThreadedAnvilChunkStorageMixin} which supplies CIS-backed NBT</li>
 * <li>{@code scanChunk} — data migration / verification scans</li>
 * <li>{@code sync} — region file buffer flushes</li>
 * </ul>
 *
 * <p>
 * <b>Important:</b> With this mixin active all chunk persistence is the
 * responsibility of the Chunkis system. If Chunkis fails to save a chunk,
 * player modifications will be lost on next load (the chunk regenerates from
 * worldgen).
 *
 * <p>
 * <b>Compatibility:</b> May conflict with mods that rely on vanilla region file
 * storage. Such mods must either integrate with Chunkis or be disabled.
 *
 * @author Liparakis
 * @version 2.1
 */
@Mixin(RegionBasedStorage.class)
public class StoragePreventionMixin {

    @Unique
    private static final Logger LOGGER = Chunkis.LOGGER;
    @Unique
    private static final String SOURCE = "StoragePreventionMixin";

    /**
     * Cancels vanilla chunk NBT writes to {@code .mca} region files.
     *
     * <p>
     * Prevents data duplication and format conflicts between vanilla and Chunkis
     * storage. Note: vanilla tools that read {@code .mca} files (NBT editors,
     * region viewers) will not see chunk data while this mixin is active.
     *
     * @param pos the chunk position attempting to be written
     * @param nbt      the NBT data (discarded — operation is cancelled)
     * @param ci       mixin callback used to cancel the operation
     */
    @Inject(
            method = "write(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/NbtCompound;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$blockWrite(
            final ChunkPos pos,
            final NbtCompound nbt,
            final CallbackInfo ci) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.SAVE_GUARDS,
                ChunkTraceEventType.VANILLA_SAVE_CANCELLED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.VANILLA_STORAGE_BLOCKED,
                SOURCE + "#chunkis$blockWrite",
                "cancelled vanilla region write",
                null,
                new DebugChunkKey(pos.x, pos.z),
                null,
                null,
                null,
                null
        );

        logTrace("Blocking vanilla chunk write for {}", pos);
        ci.cancel();
    }

    /**
     * Cancels vanilla chunk NBT reads and returns null.
     *
     * <p>
     * Returning null signals to Minecraft's chunk loading pipeline that this chunk
     * does not exist in vanilla storage, causing the pipeline to fall through to
     * worldgen. {@code ThreadedAnvilChunkStorageMixin} intercepts that path and
     * provides CIS-backed NBT instead.
     *
     * @param pos the chunk position attempting to be read
     * @param cir      callback whose return value is set to null
     */
    @Inject(
            method = "getTagAt(Lnet/minecraft/util/math/ChunkPos;)Lnet/minecraft/nbt/NbtCompound;",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$blockGetTagAt(
            final ChunkPos pos,
            final CallbackInfoReturnable<NbtCompound> cir) {
        logTrace("Blocking vanilla chunk read for {}", pos);
        cir.setReturnValue(null);
    }

    /**
     * Cancels chunk scan operations used for data migration and validation.
     *
     * <p>
     * Prevents vanilla systems from attempting to upgrade or validate chunks that
     * do not exist in the region file format. May produce warnings during world
     * upgrades or {@code /data} commands.
     *
     * @param chunkPos the chunk position attempting to be scanned
     * @param scanner  the NBT scanner (discarded — operation is cancelled)
     * @param ci       mixin callback used to cancel the operation
     */
    @Inject(
            method = "scanChunk(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/scanner/NbtScanner;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$blockScanChunk(
            final ChunkPos chunkPos,
            final NbtScanner scanner,
            final CallbackInfo ci) {
        logTrace("Blocking vanilla chunk scan for {}", chunkPos);
        ci.cancel();
    }

    /**
     * Cancels region file buffer sync operations.
     *
     * <p>
     * Since all chunk data is handled by Chunkis, vanilla sync operations would
     * produce unnecessary disk I/O on empty or absent region files.
     *
     * @param ci mixin callback used to cancel the operation
     */
    @Inject(
            method = "sync()V",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$blockSync(final CallbackInfo ci) {
        logTrace("Blocking vanilla storage sync", null);
        ci.cancel();
    }

    /**
     * Emits a TRACE-level log entry. No-ops when trace logging is disabled,
     * avoiding string-formatting overhead on the hot path.
     *
     * @param message the log message pattern (one {@code {}} placeholder, or none)
     * @param arg     a single argument to interpolate, or null for zero-argument messages
     */
    @Unique
    private static void logTrace(final String message, final Object arg) {
        if (!LOGGER.isTraceEnabled()) return;
        if (arg != null) {
            LOGGER.trace(message, arg);
        } else {
            LOGGER.trace(message);
        }
    }
}
