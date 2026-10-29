package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes whether a world chunk is still part of the world; vanilla clears it just before a chunk's unload
 * save.
 */
@Mixin(WorldChunk.class)
public interface WorldChunkAccessor {

    /**
     * Returns whether the chunk is loaded to the world.
     *
     * @return false once vanilla began unloading the chunk
     */
    @Accessor("loadedToWorld")
    boolean chunkis$isLoadedToWorld();
}
