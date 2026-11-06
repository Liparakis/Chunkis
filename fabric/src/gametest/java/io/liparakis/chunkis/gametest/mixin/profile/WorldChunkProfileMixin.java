package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the steps of making a chunk full that cost the most: entities, block entities and tick schedulers.
 */
@Mixin(LevelChunk.class)
public abstract class WorldChunkProfileMixin {

    /**
     * Opens entity loading.
     *
     * @param ci callback
     */
    @Inject(method = "runPostLoad", at = @At("HEAD"))
    private void chunkis$beginEntities(final CallbackInfo ci) {
        StageProfiler.begin(Stage.LOAD_ENTITIES);
    }

    /**
     * Closes entity loading.
     *
     * @param ci callback
     */
    @Inject(method = "runPostLoad", at = @At("RETURN"))
    private void chunkis$endEntities(final CallbackInfo ci) {
        StageProfiler.end(Stage.LOAD_ENTITIES);
    }

    /**
     * Opens block-entity setup.
     *
     * @param ci callback
     */
    @Inject(method = "registerAllBlockEntitiesAfterLevelLoad", at = @At("HEAD"))
    private void chunkis$beginBlockEntities(final CallbackInfo ci) {
        StageProfiler.begin(Stage.BLOCK_ENTITIES);
    }

    /**
     * Closes block-entity setup.
     *
     * @param ci callback
     */
    @Inject(method = "registerAllBlockEntitiesAfterLevelLoad", at = @At("RETURN"))
    private void chunkis$endBlockEntities(final CallbackInfo ci) {
        StageProfiler.end(Stage.BLOCK_ENTITIES);
    }

    /**
     * Opens tick scheduler setup.
     *
     * @param ci callback
     */
    @Inject(method = "registerTickContainerInLevel", at = @At("HEAD"))
    private void chunkis$beginSchedulers(final CallbackInfo ci) {
        StageProfiler.begin(Stage.TICK_SCHEDULERS);
    }

    /**
     * Closes tick scheduler setup.
     *
     * @param ci callback
     */
    @Inject(method = "registerTickContainerInLevel", at = @At("RETURN"))
    private void chunkis$endSchedulers(final CallbackInfo ci) {
        StageProfiler.end(Stage.TICK_SCHEDULERS);
    }
}
