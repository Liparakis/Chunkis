package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers where each delta last went from clean to dirty and back, to explain deltas the tracker keeps after
 * their save.
 */
@Mixin(value = ChunkDelta.class, remap = false)
public abstract class ChunkDeltaProbeMixin {

    /**
     * Records the call site when a dirty delta is about to be marked saved.
     *
     * @param ci callback
     */
    @Inject(method = "markSaved", at = @At("HEAD"))
    private void chunkis$recordCleanSite(final CallbackInfo ci) {
        if (StageProfiler.ENABLED && ((ChunkDelta<?, ?>) (Object) this).isDirty()) {
            StageProfiler.recordCleanSite(this);
        }
    }

    /**
     * Records the call site when a dirty delta is about to be marked saved at a generation.
     *
     * @param generation generation saved
     * @param cir        callback
     */
    @Inject(method = "markSavedIfGeneration", at = @At("HEAD"))
    private void chunkis$recordConditionalCleanSite(final long generation,
            final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (StageProfiler.ENABLED && ((ChunkDelta<?, ?>) (Object) this).isDirty()
                && ((ChunkDelta<?, ?>) (Object) this).getMutationGeneration() == generation) {
            StageProfiler.recordCleanSite(this);
        }
    }

    /**
     * Records the call site when a clean delta is about to become dirty.
     *
     * @param ci callback
     */
    @Inject(method = "markDirtyInternal", at = @At("HEAD"))
    private void chunkis$recordDirtySite(final CallbackInfo ci) {
        if (StageProfiler.ENABLED && !((ChunkDelta<?, ?>) (Object) this).isDirty()) {
            StageProfiler.recordDirtySite(this);
        }
    }
}
