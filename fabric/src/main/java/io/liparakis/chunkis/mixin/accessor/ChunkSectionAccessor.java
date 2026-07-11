package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkSection.class)
public interface ChunkSectionAccessor {

    /** Performs accessor. */
    @Accessor("nonEmptyBlockCount")
    void chunkis$setNonEmptyBlockCount(short count);

    /** Performs accessor. */
    @Accessor("nonEmptyBlockCount")
    short chunkis$getNonEmptyBlockCount();

    /** Performs accessor. */
    @Accessor("randomTickableBlockCount")
    short chunkis$getRandomTickableBlockCount();

    /** Performs accessor. */
    @Accessor("nonEmptyFluidCount")
    short chunkis$getNonEmptyFluidCount();

    /** Performs accessor. */
    @Accessor("randomTickableBlockCount")
    void chunkis$setRandomTickableBlockCount(short count);

    /** Performs accessor. */
    @Accessor("nonEmptyFluidCount")
    void chunkis$setNonEmptyFluidCount(short count);
}
