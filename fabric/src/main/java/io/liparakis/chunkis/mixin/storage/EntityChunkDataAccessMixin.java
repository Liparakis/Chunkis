package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.world.entity.capture.VanillaEntitySaveCapture;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands vanilla's per-chunk entity save to {@link VanillaEntitySaveCapture}.
 *
 * <p>Runs at {@code HEAD}, before entities are unloaded, so the snapshot sees
 * them live. When the region guard would drop vanilla's write anyway, vanilla
 * does not serialize the entities a second time just to have them dropped; an
 * empty list goes on to vanilla, which only records that the chunk has none.</p>
 */
@Mixin(EntityStorage.class)
public abstract class EntityChunkDataAccessMixin {

    /**
     * World whose entities this storage persists.
     */
    @Shadow
    @Final
    private ServerLevel level;

    /**
     * Chunks vanilla knows to hold no entities.
     */
    @Shadow
    @Final
    private LongSet emptyChunks;

    /**
     * Captures the saved entities into the Chunkis delta.
     *
     * @param dataList chunk position and the entities vanilla is saving
     * @param ci       mixin callback
     */
    @Inject(method = "storeEntities", at = @At("HEAD"), cancellable = true)
    private void chunkis$captureSavedEntities(final ChunkEntities<Entity> dataList, final CallbackInfo ci) {
        VanillaEntitySaveCapture.capture(level, dataList.getPos(), dataList.getEntities()
                .toList());
        if (!dataList.isEmpty() && VanillaEntitySaveCapture.isVanillaWriteBlocked(level)) {
            emptyChunks.remove(dataList.getPos().pack());
            ci.cancel();
        }
    }
}
