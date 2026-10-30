package io.liparakis.chunkis.gametest.mixin;

import io.liparakis.chunkis.gametest.stress.StressBenchGameTest;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the base-chunk capture in vanilla mode: its result is thrown away there, and it would bill vanilla for work
 * only Chunkis does.
 */
@Mixin(value = BaseChunkCaptureUtil.class, remap = false)
public abstract class VanillaModeBaseCaptureMixin {

    /**
     * Skips the capture in vanilla mode.
     *
     * @param ci callback
     */
    @Inject(method = "captureBaseChunkIfMissing", at = @At("HEAD"), cancellable = true)
    private static void chunkis$skipInVanillaMode(final CallbackInfo ci) {
        if (StressBenchGameTest.isVanillaStorageMode()) {
            ci.cancel();
        }
    }
}
