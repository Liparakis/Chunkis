package io.liparakis.chunkis.gametest.mixin;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.gametest.stress.StressBenchGameTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the stress benchmark measure vanilla storage: while it runs in vanilla mode, no chunk can become
 * Chunkis-owned, so every chunk saves and loads through vanilla's region files.
 */
@Mixin(ChunkDelta.class)
public abstract class VanillaStorageModeMixin {

    /**
     * Refuses ownership claims in vanilla mode.
     *
     * @param reason ownership reason
     * @param source claiming code path
     * @param cir    callback returning whether the claim succeeded
     */
    @Inject(method = "claimOwnership", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunkis$refuseInVanillaMode(final String reason, final String source,
            final CallbackInfoReturnable<Boolean> cir) {
        if (StressBenchGameTest.isVanillaStorageMode()) {
            cir.setReturnValue(false);
        }
    }
}
