package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.gametest.stress.WriteVolume;
import io.liparakis.chunkis.storage.io.region.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts Chunkis region writes (chunks and unfinished chunks); entries take exactly their bytes. Deletes, which
 * reach the same method with a null payload, are not counted.
 */
@Mixin(value = RegionFile.class, remap = false)
public abstract class CisRegionWriteVolumeMixin {

    /**
     * Counts a chunk write.
     *
     * @param pos         chunk position
     * @param data        payload
     * @param operationId trace id
     * @param ci          callback
     */
    @Inject(method = "write(Lio/liparakis/chunkis/core/CisChunkPos;[BLjava/lang/String;)V", at = @At("HEAD"))
    private void chunkis$count(final CisChunkPos pos, final byte[] data, final String operationId,
            final CallbackInfo ci) {
        // A null payload is a delete (CompressedRegionStore#delete).
        if (WriteVolume.ENABLED && data != null) {
            final String store = ((RegionFile) (Object) this).path().getParent().getFileName().toString();
            WriteVolume.record("chunkis/" + store, data.length, data.length);
        }
    }
}
