package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.ownership.PendingVanillaSaveDecision;
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
 * Blocks vanilla MCA I/O only when Chunkis explicitly owns the chunk lifecycle.
 */
@Mixin(RegionBasedStorage.class)
public class StoragePreventionMixin {

    /**
     * Logger instance for writing storage prevention logs.
     */
    @Unique
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Trace source identification tag label.
     */
    @Unique
    private static final String SOURCE = "StoragePreventionMixin";

    /**
     * Default constructor for StoragePreventionMixin.
     */
    public StoragePreventionMixin() {
    }

    /**
     * Returns whether the current vanilla write should be cancelled in favor of
     * Chunkis persistence.
     *
     * @param snapshot pending save decision carried from the higher-level save path
     * @return {@code true} only when Chunkis explicitly owns the write
     */
    @Unique
    private static boolean chunkis$shouldBlockVanillaWrite(final PendingVanillaSaveDecision.Snapshot snapshot) {
        return snapshot != null && snapshot.reason() != ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED;
    }

    /**
     * Injects at head of RegionBasedStorage#write to cancel only Chunkis-owned
     * vanilla saves.
     *
     * @param pos target chunk position
     * @param nbt target chunk NBT payload
     * @param ci  callback info helper
     */
    @Inject(method = "write(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/NbtCompound;)V", at = @At("HEAD"),
            cancellable = true)
    private void chunkis$blockWrite(final ChunkPos pos, final NbtCompound nbt, final CallbackInfo ci) {
        final PendingVanillaSaveDecision.Snapshot snapshot = PendingVanillaSaveDecision.take(pos);
        final ChunkTraceReason reason = snapshot != null ? snapshot.reason() :
                ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED;
        if (!chunkis$shouldBlockVanillaWrite(snapshot)) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Allowing vanilla MCA write for {}", pos);
            }
            return;
        }
        ChunkOwnershipTraceHelper.traceDecision(
                null, pos, "BYPASSED", reason, SOURCE + "#chunkis$blockWrite",
                snapshot.delta(), null
        );

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.VANILLA_SAVE_CANCELLED,
                ChunkTraceSeverity.INFO, reason, SOURCE + "#chunkis$blockWrite", "blocked vanilla MCA write", null,
                new DebugChunkKey(pos.x, pos.z), null, null, null, null
        );
        ci.cancel();
    }

    /**
     * Leaves vanilla chunk reads intact.
     *
     * @param pos target chunk position
     * @param cir callback info returnable wrapper
     */
    @Inject(method = "getTagAt(Lnet/minecraft/util/math/ChunkPos;)Lnet/minecraft/nbt/NbtCompound;", at = @At("HEAD"))
    private void chunkis$traceGetTagAt(final ChunkPos pos, final CallbackInfoReturnable<NbtCompound> cir) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Allowing vanilla MCA read for {}", pos);
        }
    }

    /**
     * Leaves vanilla chunk scans intact.
     *
     * @param chunkPos target chunk position
     * @param scanner  NBT scanner instance
     * @param ci       callback info helper
     */
    @Inject(method = "scanChunk(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/scanner/NbtScanner;)V", at =
    @At("HEAD"))
    private void chunkis$traceScanChunk(final ChunkPos chunkPos, final NbtScanner scanner, final CallbackInfo ci) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Allowing vanilla MCA scan for {}", chunkPos);
        }
    }

    /**
     * Leaves vanilla region sync intact while documenting the boundary.
     *
     * @param ci callback info helper
     */
    @Inject(method = "sync()V", at = @At("HEAD"))
    private void chunkis$blockSync(final CallbackInfo ci) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Allowing vanilla storage sync");
        }
    }
}
