package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times building the chunk NBT a load hands vanilla, at the overload every load build goes through.
 */
@Mixin(value = CisNbtUtil.class, remap = false)
public abstract class CisNbtUtilProfileMixin {

    /**
     * Opens the stage.
     *
     * @param cb callback
     */
    @Inject(method = "buildLoadChunkNbt(IIILio/liparakis/chunkis/core/ChunkDelta;)Lio/liparakis/chunkis/world/restoration/nbt/CisNbtUtil$LoadChunkNbtResult;", at = @At("HEAD"))
    private static void chunkis$begin(final CallbackInfoReturnable<?> cb) {
        StageProfiler.begin(Stage.NBT_BUILD);
    }

    /**
     * Closes the stage.
     *
     * @param cb callback
     */
    @Inject(method = "buildLoadChunkNbt(IIILio/liparakis/chunkis/core/ChunkDelta;)Lio/liparakis/chunkis/world/restoration/nbt/CisNbtUtil$LoadChunkNbtResult;", at = @At("RETURN"))
    private static void chunkis$end(final CallbackInfoReturnable<?> cb) {
        StageProfiler.end(Stage.NBT_BUILD);
    }
}
