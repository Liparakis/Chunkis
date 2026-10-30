package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Event;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.SerializedChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times vanilla's NBT parse (section construction included) and its conversion into a proto chunk; Chunkis's own
 * hooks on both run inside the timed span.
 */
@Mixin(SerializedChunk.class)
public abstract class SerializedChunkProfileMixin {

    /**
     * Opens the parse.
     *
     * @param cir callback
     */
    @Inject(method = "fromNbt", at = @At("HEAD"))
    private static void chunkis$beginParse(final CallbackInfoReturnable<SerializedChunk> cir) {
        StageProfiler.begin(Stage.PARSE);
    }

    /**
     * Closes the parse.
     *
     * @param cir callback holding the parsed chunk
     */
    @Inject(method = "fromNbt", at = @At("RETURN"))
    private static void chunkis$endParse(final CallbackInfoReturnable<SerializedChunk> cir) {
        StageProfiler.end(Stage.PARSE);
        final SerializedChunk parsed = cir.getReturnValue();
        if (parsed != null) {
            StageProfiler.event(Event.PARSED, parsed.chunkPos().x, parsed.chunkPos().z);
        }
    }

    /**
     * Opens the conversion.
     *
     * @param cir callback
     */
    @Inject(method = "convert", at = @At("HEAD"))
    private void chunkis$beginConvert(final CallbackInfoReturnable<ProtoChunk> cir) {
        StageProfiler.begin(Stage.CONVERT);
    }

    /**
     * Closes the conversion.
     *
     * @param cir callback
     */
    @Inject(method = "convert", at = @At("RETURN"))
    private void chunkis$endConvert(final CallbackInfoReturnable<ProtoChunk> cir) {
        StageProfiler.end(Stage.CONVERT);
        final ChunkPos pos = ((SerializedChunk) (Object) this).chunkPos();
        StageProfiler.event(Event.CONVERTED, pos.x, pos.z);
    }
}
