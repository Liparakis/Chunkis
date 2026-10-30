package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import io.liparakis.chunkis.world.tracking.save.UnfinishedChunkStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times saving a chunk into the unfinished-chunk store, which serializes it on the calling thread.
 */
@Mixin(value = UnfinishedChunkStore.class, remap = false)
public abstract class UnfinishedSaveProfileMixin {

    /**
     * Opens the stage.
     *
     * @param cir callback
     */
    @Inject(method = "save", at = @At("HEAD"))
    private void chunkis$begin(final CallbackInfoReturnable<Boolean> cir) {
        StageProfiler.begin(Stage.UNFINISHED_SAVE);
    }

    /**
     * Closes the stage.
     *
     * @param cir callback
     */
    @Inject(method = "save", at = @At("RETURN"))
    private void chunkis$end(final CallbackInfoReturnable<Boolean> cir) {
        StageProfiler.end(Stage.UNFINISHED_SAVE);
    }
}
