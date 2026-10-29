package io.liparakis.chunkis.mixin.compat;

import io.liparakis.chunkis.integration.c2me.C2meSaveBridge;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Offers chunks C2ME's chunk system saves on unload to Chunkis first; see {@link C2meSaveBridge}. Skipped when
 * C2ME isn't installed.
 */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDisk", remap = false)
public abstract class C2meReadFromDiskMixin {

    /**
     * Not instantiated.
     */
    private C2meReadFromDiskMixin() {
    }

    /**
     * Lets Chunkis save the chunk, and skips C2ME's own save when it did.
     *
     * @param loadingContext C2ME's chunk loading context
     * @param chunk          the chunk being saved
     * @param cir            C2ME's save result
     */
    @Inject(method = "asyncSave", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void chunkis$saveThroughChunkis(@Coerce final Object loadingContext, final Chunk chunk,
            final CallbackInfoReturnable<Object> cir) {
        final Object handled = C2meSaveBridge.saveThroughChunkis(loadingContext, chunk);
        if (handled != null) {
            cir.setReturnValue(handled);
        }
    }
}
