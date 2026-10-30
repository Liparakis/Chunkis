package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers where a clean delta was put into the dirty-delta tracker, which nothing removes again.
 */
@Mixin(value = GlobalChunkTracker.class, remap = false)
public abstract class GlobalChunkTrackerProbeMixin {

    /**
     * Records the call site when a clean delta enters the tracker.
     *
     * @param key    tracker key
     * @param delta  the delta
     * @param source mutation source
     * @param chunk  live chunk, if any
     * @param ci     callback
     */
    @Inject(method = "putDeltaIfNeeded", at = @At("HEAD"))
    private static void chunkis$recordCleanPut(final @org.spongepowered.asm.mixin.injection.Coerce Object key,
            final ChunkDelta<?, ?> delta, final String source, final net.minecraft.world.chunk.WorldChunk chunk,
            final CallbackInfo ci) {
        if (StageProfiler.ENABLED && delta != null && !delta.isDirty()) {
            StageProfiler.recordCleanPut(delta, source);
        }
    }
}
