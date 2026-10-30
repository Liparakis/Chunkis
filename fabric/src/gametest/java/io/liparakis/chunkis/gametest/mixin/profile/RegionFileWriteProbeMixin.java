package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.storage.io.region.RegionFile;
import java.io.IOException;
import java.util.Arrays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Compares every region write with the entry it replaces, to count writes that store exactly what is already
 * there. Zstd output is deterministic for the same input and level, so equal bytes mean an unchanged chunk. Costs
 * an extra read per write, so it runs only with {@code -Dchunkis.stress.probeWrites=true}.
 */
@Mixin(value = RegionFile.class, remap = false)
public abstract class RegionFileWriteProbeMixin {

    /**
     * Whether the probe runs.
     */
    private static final boolean CHUNKIS$PROBE = Boolean.getBoolean("chunkis.stress.probeWrites");

    /**
     * Classifies a write against the stored entry before it is replaced.
     *
     * @param pos         chunk position
     * @param data        bytes about to be written
     * @param operationId trace id
     * @param ci          callback
     * @throws IOException when the stored entry cannot be read
     */
    @Inject(method = "write(Lio/liparakis/chunkis/core/CisChunkPos;[BLjava/lang/String;)V", at = @At("HEAD"))
    private void chunkis$compareWithStored(final CisChunkPos pos, final byte[] data, final String operationId,
            final CallbackInfo ci) throws IOException {
        // A null payload is a delete (CompressedRegionStore#delete), not a write.
        if (!CHUNKIS$PROBE || data == null) {
            return;
        }
        final RegionFile self = (RegionFile) (Object) this;
        final boolean unfinished = self.path().toString().contains("unfinished");
        final byte[] stored = self.read(pos);
        StageProfiler.recordWrite(unfinished, stored == null ? StageProfiler.WriteKind.NEW
                : Arrays.equals(stored, data) ? StageProfiler.WriteKind.IDENTICAL : StageProfiler.WriteKind.CHANGED,
                data.length);
    }
}
