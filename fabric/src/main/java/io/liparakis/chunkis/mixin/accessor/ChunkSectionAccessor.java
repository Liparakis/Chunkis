package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkSection.class)
public interface ChunkSectionAccessor {

    @Accessor("nonEmptyBlockCount")
    void chunkis$setNonEmptyBlockCount(short count);

    @Accessor("nonEmptyBlockCount")
    short chunkis$getNonEmptyBlockCount();

    @Accessor("randomTickableBlockCount")
    short chunkis$getRandomTickableBlockCount();

    @Accessor("nonEmptyFluidCount")
    short chunkis$getNonEmptyFluidCount();

    @Accessor("randomTickableBlockCount")
    void chunkis$setRandomTickableBlockCount(short count);

    @Accessor("nonEmptyFluidCount")
    void chunkis$setNonEmptyFluidCount(short count);
}
