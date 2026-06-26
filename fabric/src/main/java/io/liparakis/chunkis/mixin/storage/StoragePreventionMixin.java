package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.storage.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.storage.PendingVanillaSaveDecision;
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

import io.liparakis.chunkis.debug.ChunkTraceReason;

/**
 * Hard-stops vanilla MCA writes.
 */
@Mixin(RegionBasedStorage.class)
public class StoragePreventionMixin {

    @Unique
    private static final Logger LOGGER = Chunkis.LOGGER;
    @Unique
    private static final String SOURCE = "StoragePreventionMixin";

    @Inject(
            method = "write(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/NbtCompound;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$blockWrite(
            final ChunkPos pos,
            final NbtCompound nbt,
            final CallbackInfo ci) {
        final PendingVanillaSaveDecision.Snapshot snapshot = PendingVanillaSaveDecision.take(pos);
        final ChunkTraceReason reason = snapshot != null
                ? snapshot.reason()
                : ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED;
        if (snapshot == null) {
            ChunkOwnershipTraceHelper.traceDecision(
                    null,
                    pos,
                    "BYPASSED",
                    reason,
                    SOURCE + "#chunkis$blockWrite",
                    null,
                    null
            );
        } else {
            ChunkOwnershipTraceHelper.traceDecision(
                    null,
                    pos,
                    "BYPASSED",
                    reason,
                    SOURCE + "#chunkis$blockWrite",
                    snapshot.delta(),
                    null
            );
        }

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.VANILLA_SAVE_CANCELLED,
                ChunkTraceSeverity.INFO,
                reason,
                SOURCE + "#chunkis$blockWrite",
                "blocked vanilla MCA write",
                null,
                new DebugChunkKey(pos.x, pos.z),
                null,
                null,
                null,
                null
        );
        ci.cancel();
    }

    /**
     * Records passive vanilla chunk read attempts without claiming ownership.
     */
    @Inject(
            method = "getTagAt(Lnet/minecraft/util/math/ChunkPos;)Lnet/minecraft/nbt/NbtCompound;",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$blockGetTagAt(
            final ChunkPos pos,
            final CallbackInfoReturnable<NbtCompound> cir) {
        ChunkOwnershipTraceHelper.traceDecision(
                null,
                pos,
                "BYPASSED",
                ChunkTraceReason.PASSIVE_VANILLA_LOAD,
                SOURCE + "#chunkis$blockGetTagAt",
                null,
                null
        );
        cir.setReturnValue(null);
    }

    /**
     * Records passive vanilla chunk scans without claiming ownership.
     */
    @Inject(
            method = "scanChunk(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/scanner/NbtScanner;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$blockScanChunk(
            final ChunkPos chunkPos,
            final NbtScanner scanner,
            final CallbackInfo ci) {
        ChunkOwnershipTraceHelper.traceDecision(
                null,
                chunkPos,
                "BYPASSED",
                ChunkTraceReason.PASSIVE_VANILLA_LOAD,
                SOURCE + "#chunkis$blockScanChunk",
                null,
                null
        );
        ci.cancel();
    }

    /**
     * Leaves vanilla region sync intact while documenting the boundary.
     */
    @Inject(
            method = "sync()V",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$blockSync(final CallbackInfo ci) {
        logTrace("Allowing vanilla storage sync", null);
    }

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
