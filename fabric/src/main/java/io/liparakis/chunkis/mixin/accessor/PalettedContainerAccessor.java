package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PalettedContainer.class)
public interface PalettedContainerAccessor<T> {

    /**
     * Performs invoker.
     */
    @Invoker("get")
    T chunkis$get(int index);
}
