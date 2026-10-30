package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import io.liparakis.chunkis.world.tracking.load.SyntheticLoadNbtProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times Chunkis's whole synthetic NBT provider: delta resolution, storage load and NBT build.
 */
@Mixin(value = SyntheticLoadNbtProvider.class, remap = false)
public abstract class SyntheticLoadNbtProviderProfileMixin {

    /**
     * Opens the stage.
     *
     * @param cb callback
     */
    @Inject(method = "provide", at = @At("HEAD"))
    private void chunkis$begin(final CallbackInfoReturnable<?> cb) {
        StageProfiler.begin(Stage.CHUNKIS_PROVIDE);
    }

    /**
     * Closes the stage.
     *
     * @param cb callback
     */
    @Inject(method = "provide", at = @At("RETURN"))
    private void chunkis$end(final CallbackInfoReturnable<?> cb) {
        StageProfiler.end(Stage.CHUNKIS_PROVIDE);
    }
}
