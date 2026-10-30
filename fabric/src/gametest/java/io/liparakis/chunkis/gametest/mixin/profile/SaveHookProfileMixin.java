package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import io.liparakis.chunkis.world.tracking.save.ChunkSaveCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times Chunkis's save hook, where a chunk's save work on the calling thread happens.
 */
@Mixin(value = ChunkSaveCoordinator.class, remap = false)
public abstract class SaveHookProfileMixin {

    /**
     * Opens the stage.
     *
     * @param cir callback
     */
    @Inject(method = "save", at = @At("HEAD"))
    private void chunkis$begin(final CallbackInfoReturnable<?> cir) {
        StageProfiler.begin(Stage.SAVE_HOOK);
    }

    /**
     * Closes the stage.
     *
     * @param cir callback
     */
    @Inject(method = "save", at = @At("RETURN"))
    private void chunkis$end(final CallbackInfoReturnable<ChunkSaveCoordinator.SaveOutcome> cir) {
        StageProfiler.endAs(Stage.SAVE_HOOK, switch (cir.getReturnValue()) {
            case HANDLED_DIRTY -> Stage.SAVE_HOOK_DIRTY;
            case HANDLED_CLEAN -> Stage.SAVE_HOOK_CLEAN;
            case VANILLA -> Stage.SAVE_HOOK_VANILLA;
        });
    }
}
