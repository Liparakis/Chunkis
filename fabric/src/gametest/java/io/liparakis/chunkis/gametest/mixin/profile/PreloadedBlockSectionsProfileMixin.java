package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import io.liparakis.chunkis.world.restoration.nbt.PreloadedBlockSections;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times attaching pre-built sections to a load's NBT.
 */
@Mixin(value = PreloadedBlockSections.class, remap = false)
public abstract class PreloadedBlockSectionsProfileMixin {

    /**
     * Opens the stage.
     *
     * @param cb callback
     */
    @Inject(method = "embed", at = @At("HEAD"))
    private static void chunkis$begin(final CallbackInfo cb) {
        StageProfiler.begin(Stage.EMBED);
    }

    /**
     * Closes the stage.
     *
     * @param cb callback
     */
    @Inject(method = "embed", at = @At("RETURN"))
    private static void chunkis$end(final CallbackInfo cb) {
        StageProfiler.end(Stage.EMBED);
    }
}
