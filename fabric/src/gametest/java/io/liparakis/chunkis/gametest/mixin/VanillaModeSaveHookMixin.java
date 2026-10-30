package io.liparakis.chunkis.gametest.mixin;

import io.liparakis.chunkis.gametest.stress.StressBenchGameTest;
import io.liparakis.chunkis.world.tracking.save.ChunkSaveCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves every save to vanilla at once in vanilla mode, so vanilla is not billed for Chunkis's save hook.
 */
@Mixin(value = ChunkSaveCoordinator.class, remap = false)
public abstract class VanillaModeSaveHookMixin {

    /**
     * Hands the save to vanilla in vanilla mode.
     *
     * @param cir callback
     */
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void chunkis$leaveToVanilla(final CallbackInfoReturnable<ChunkSaveCoordinator.SaveOutcome> cir) {
        if (StressBenchGameTest.isVanillaStorageMode()) {
            cir.setReturnValue(ChunkSaveCoordinator.SaveOutcome.VANILLA);
        }
    }
}
