package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers where each delta last went from clean to dirty, to explain deltas the tracker keeps after
 * their save.
 */
@Mixin(value = ChunkDelta.class, remap = false)
@SuppressWarnings({"DataFlowIssue", "ConstantValue"})
public abstract class ChunkDeltaProbeMixin {

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
