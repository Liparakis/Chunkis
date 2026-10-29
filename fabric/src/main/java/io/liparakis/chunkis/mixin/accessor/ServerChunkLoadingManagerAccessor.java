package io.liparakis.chunkis.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the holders of chunks that left the loaded set but whose unload save has not run yet. Vanilla can
 * save their entities in that window.
 */
@Mixin(ServerChunkLoadingManager.class)
public interface ServerChunkLoadingManagerAccessor {

    /**
     * Returns the holders waiting for their unload save, by packed chunk position. Server thread only.
     *
     * @return the holders waiting to unload
     */
    @Accessor("chunksToUnload")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> chunkis$getChunksToUnload();
}
