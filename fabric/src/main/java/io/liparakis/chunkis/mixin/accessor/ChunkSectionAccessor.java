package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkSection.class)
public interface ChunkSectionAccessor {

    @Accessor("nonEmptyBlockCount")
    short chunkis$getNonEmptyBlockCount();

    @Accessor("nonEmptyBlockCount")
    void chunkis$setNonEmptyBlockCount(short count);

    @Accessor("randomTickableBlockCount")
    void chunkis$setRandomTickableBlockCount(short count);

    @Accessor("nonEmptyFluidCount")
    void chunkis$setNonEmptyFluidCount(short count);
}
