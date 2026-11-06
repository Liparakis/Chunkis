package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Event;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times vanilla's NBT parse (section construction included) and its conversion into a proto chunk; Chunkis's own
 * hooks on both run inside the timed span.
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializedChunkProfileMixin {

    /**
     * Opens vanilla's save serialization.
     *
     * @param cir callback
     */
    @Inject(method = "copyOf", at = @At("HEAD"))
    private static void chunkis$beginSerialize(final CallbackInfoReturnable<SerializableChunkData> cir) {
        StageProfiler.begin(Stage.VANILLA_SERIALIZE);
    }

    /**
     * Closes vanilla's save serialization.
     *
     * @param cir callback
     */
    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void chunkis$endSerialize(final CallbackInfoReturnable<SerializableChunkData> cir) {
        StageProfiler.end(Stage.VANILLA_SERIALIZE);
    }

    /**
     * Opens the parse.
     *
     * @param cir callback
     */
    @Inject(method = "parse", at = @At("HEAD"))
    private static void chunkis$beginParse(final CallbackInfoReturnable<SerializableChunkData> cir) {
        StageProfiler.begin(Stage.PARSE);
    }

    /**
     * Closes the parse.
     *
     * @param cir callback holding the parsed chunk
     */
    @Inject(method = "parse", at = @At("RETURN"))
    private static void chunkis$endParse(final CallbackInfoReturnable<SerializableChunkData> cir) {
        StageProfiler.end(Stage.PARSE);
        final SerializableChunkData parsed = cir.getReturnValue();
        if (parsed != null) {
            StageProfiler.event(Event.PARSED, parsed.chunkPos().x(), parsed.chunkPos().z());
        }
    }

    /**
     * Opens the conversion.
     *
     * @param cir callback
     */
    @Inject(method = "read", at = @At("HEAD"))
    private void chunkis$beginConvert(final CallbackInfoReturnable<ProtoChunk> cir) {
        StageProfiler.begin(Stage.CONVERT);
    }

    /**
     * Closes the conversion.
     *
     * @param cir callback
     */
    @Inject(method = "read", at = @At("RETURN"))
    private void chunkis$endConvert(final CallbackInfoReturnable<ProtoChunk> cir) {
        StageProfiler.end(Stage.CONVERT);
        final ChunkPos pos = ((SerializableChunkData) (Object) this).chunkPos();
        StageProfiler.event(Event.CONVERTED, pos.x(), pos.z());
    }
}
