package io.liparakis.chunkis.gametest.mixin.profile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import io.liparakis.chunkis.storage.io.CisStorage;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times the storage stages of a chunk load: region read, decompression and filling a delta from cached blocks.
 */
@Mixin(value = CisStorage.class, remap = false)
public abstract class CisStorageProfileMixin {

    /**
     * Opens the region read.
     *
     * @param cir callback
     */
    @Inject(method = "readRegion", at = @At("HEAD"))
    private void chunkis$beginRead(final CallbackInfoReturnable<?> cir) {
        StageProfiler.begin(Stage.IO);
    }

    /**
     * Closes the region read.
     *
     * @param cir callback
     */
    @Inject(method = "readRegion", at = @At("RETURN"))
    private void chunkis$endRead(final CallbackInfoReturnable<?> cir) {
        StageProfiler.end(Stage.IO);
    }

    /**
     * Opens decompression.
     *
     * @param cir callback
     */
    @Inject(method = "decompressEntry", at = @At("HEAD"))
    private void chunkis$beginDecompress(final CallbackInfoReturnable<?> cir) {
        StageProfiler.begin(Stage.DECOMPRESS);
    }

    /**
     * Closes decompression.
     *
     * @param cir callback
     */
    @Inject(method = "decompressEntry", at = @At("RETURN"))
    private void chunkis$endDecompress(final CallbackInfoReturnable<?> cir) {
        StageProfiler.end(Stage.DECOMPRESS);
    }

    /**
     * Times filling a delta from the recent-chunk cache's sections.
     *
     * @param fill     the cache's fill
     * @param delta    delta being filled
     * @param original the call
     */
    @WrapOperation(method = "decodeCompressed", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void chunkis$timeCacheFill(final Consumer<Object> fill, final Object delta,
            final Operation<Void> original) {
        StageProfiler.begin(Stage.CACHE_FILL);
        original.call(fill, delta);
        StageProfiler.end(Stage.CACHE_FILL);
    }
}
