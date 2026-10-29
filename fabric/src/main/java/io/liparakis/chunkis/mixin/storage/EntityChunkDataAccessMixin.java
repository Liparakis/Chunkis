package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.world.entity.capture.VanillaEntitySaveCapture;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.storage.ChunkDataList;
import net.minecraft.world.storage.EntityChunkDataAccess;
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
 * them live. The vanilla write itself is left alone; the region guard decides
 * whether it reaches disk.</p>
 */
@Mixin(EntityChunkDataAccess.class)
public abstract class EntityChunkDataAccessMixin {

    /**
     * World whose entities this storage persists.
     */
    @Shadow
    @Final
    private ServerWorld world;

    /**
     * Captures the saved entities into the Chunkis delta.
     *
     * @param dataList chunk position and the entities vanilla is saving
     * @param ci       mixin callback
     */
    @Inject(method = "writeChunkData", at = @At("HEAD"))
    private void chunkis$captureSavedEntities(final ChunkDataList<Entity> dataList, final CallbackInfo ci) {
        VanillaEntitySaveCapture.capture(world, dataList.getChunkPos(), dataList.stream()
                .toList());
    }
}
