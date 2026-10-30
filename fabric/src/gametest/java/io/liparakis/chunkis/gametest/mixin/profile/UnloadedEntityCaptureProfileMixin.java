package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import io.liparakis.chunkis.world.entity.capture.VanillaEntitySaveCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times attaching saved entities to an unloaded chunk, which may read the chunk's stored entry.
 */
@Mixin(value = VanillaEntitySaveCapture.class, remap = false)
public abstract class UnloadedEntityCaptureProfileMixin {

    /**
     * Opens the stage.
     *
     * @param ci callback
     */
    @Inject(method = "captureUnloaded", at = @At("HEAD"))
    private static void chunkis$begin(final CallbackInfo ci) {
        StageProfiler.begin(Stage.UNLOADED_ENTITY_CAPTURE);
    }

    /**
     * Closes the stage.
     *
     * @param ci callback
     */
    @Inject(method = "captureUnloaded", at = @At("RETURN"))
    private static void chunkis$end(final CallbackInfo ci) {
        StageProfiler.end(Stage.UNLOADED_ENTITY_CAPTURE);
    }
}
