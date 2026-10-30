package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.core.codec.CisDecoder;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times CIS decoding, with and without blocks.
 */
@Mixin(value = CisDecoder.class, remap = false)
public abstract class CisDecoderProfileMixin {

    /**
     * Opens a full decode.
     *
     * @param cir callback
     */
    @Inject(method = "decode", at = @At("HEAD"))
    private void chunkis$beginDecode(final CallbackInfoReturnable<?> cir) {
        StageProfiler.begin(Stage.DECODE);
    }

    /**
     * Closes a full decode.
     *
     * @param cir callback
     */
    @Inject(method = "decode", at = @At("RETURN"))
    private void chunkis$endDecode(final CallbackInfoReturnable<?> cir) {
        StageProfiler.end(Stage.DECODE);
    }

    /**
     * Opens a decode without blocks.
     *
     * @param cir callback
     */
    @Inject(method = "decodeWithoutBlocks", at = @At("HEAD"))
    private void chunkis$beginDecodeWithoutBlocks(final CallbackInfoReturnable<?> cir) {
        StageProfiler.begin(Stage.DECODE_WITHOUT_BLOCKS);
    }

    /**
     * Closes a decode without blocks.
     *
     * @param cir callback
     */
    @Inject(method = "decodeWithoutBlocks", at = @At("RETURN"))
    private void chunkis$endDecodeWithoutBlocks(final CallbackInfoReturnable<?> cir) {
        StageProfiler.end(Stage.DECODE_WITHOUT_BLOCKS);
    }
}
