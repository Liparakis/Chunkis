package io.liparakis.chunkis.mixin.accessor;

import java.util.Map;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Chunk.class)
public interface ChunkBlockEntityNbtAccessor {

    @Accessor("blockEntityNbts")
    Map<BlockPos, NbtCompound> chunkis$getBlockEntityNbts();
}
