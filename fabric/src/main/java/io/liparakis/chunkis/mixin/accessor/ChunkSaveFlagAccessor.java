package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads vanilla's own unsaved flag, which {@code needsSaving()} cannot show since Chunkis also reports its dirty
 * delta through it.
 */
@Mixin(Chunk.class)
public interface ChunkSaveFlagAccessor {

    /**
     * Returns vanilla's unsaved flag: set by changes to blocks, block entities, light and structures, and cleared
     * by each save. Entity changes and newly scheduled ticks do not set it.
     *
     * @return whether vanilla considers the chunk changed since its last save
     */
    @Accessor("needsSaving")
    boolean chunkis$vanillaNeedsSaving();
}
