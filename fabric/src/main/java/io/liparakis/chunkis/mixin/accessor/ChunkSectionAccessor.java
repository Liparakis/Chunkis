package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunkSection.class)
public interface ChunkSectionAccessor {

    /**
     * Performs accessor.
     */
    @Accessor("nonEmptyBlockCount")
    void chunkis$setNonEmptyBlockCount(short count);

    /**
     * Performs accessor.
     */
    @Accessor("nonEmptyBlockCount")
    short chunkis$getNonEmptyBlockCount();

    /**
     * Performs accessor.
     */
    @Accessor("tickingBlockCount")
    void chunkis$setRandomTickableBlockCount(short count);

    /**
     * Performs accessor.
     */
    @Accessor("tickingFluidCount")
    void chunkis$setNonEmptyFluidCount(short count);
}
