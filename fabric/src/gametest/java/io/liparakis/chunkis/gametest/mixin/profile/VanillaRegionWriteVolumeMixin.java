package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.gametest.stress.WriteVolume;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionFile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts vanilla region writes (chunks, entities, POI), payload and the whole 4 KiB sectors they take.
 */
@Mixin(RegionFile.class)
public abstract class VanillaRegionWriteVolumeMixin {

    /**
     * The region file's path.
     */
    @Shadow
    @Final
    private Path path;

    /**
     * Counts a chunk write.
     *
     * @param pos chunk position
     * @param buf payload, header included
     * @param ci  callback
     */
    @Inject(method = "writeChunk", at = @At("HEAD"))
    private void chunkis$count(final ChunkPos pos, final ByteBuffer buf, final CallbackInfo ci) {
        if (WriteVolume.ENABLED) {
            final int bytes = buf.remaining();
            WriteVolume.record("vanilla/" + path.getParent().getFileName(), bytes, (bytes + 4095L) / 4096 * 4096);
        }
    }
}
