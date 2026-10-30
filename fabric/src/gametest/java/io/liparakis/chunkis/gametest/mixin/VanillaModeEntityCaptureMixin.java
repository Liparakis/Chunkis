package io.liparakis.chunkis.gametest.mixin;

import io.liparakis.chunkis.gametest.stress.StressBenchGameTest;
import io.liparakis.chunkis.world.entity.capture.VanillaEntitySaveCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips Chunkis's copy of vanilla's entity saves in vanilla mode, where vanilla's own write is the only one needed.
 */
@Mixin(value = VanillaEntitySaveCapture.class, remap = false)
public abstract class VanillaModeEntityCaptureMixin {

    /**
     * Skips the capture in vanilla mode.
     *
     * @param ci callback
     */
    @Inject(method = "capture", at = @At("HEAD"), cancellable = true)
    private static void chunkis$skipInVanillaMode(final CallbackInfo ci) {
        if (StressBenchGameTest.isVanillaStorageMode()) {
            ci.cancel();
        }
    }
}
