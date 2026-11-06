package io.liparakis.chunkis.mixin.world.chunk;

import io.liparakis.chunkis.world.tracking.settle.FreshChunkDuck;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks proto chunks the generator creates, as opposed to ones read from storage; see
 * {@link io.liparakis.chunkis.world.tracking.settle.GenerationSettling}.
 */
@Mixin(ProtoChunk.class)
@SuppressWarnings({"ConstantValue"})
public class ProtoChunkMixin implements FreshChunkDuck {

    /**
     * Whether the generator created this chunk in this session.
     */
    @Unique
    private boolean chunkis$freshlyGenerated;

    @Override
    public boolean chunkis$isFreshlyGenerated() {
        return chunkis$freshlyGenerated;
    }

    @Override
    public void chunkis$markFreshlyGenerated() {
        this.chunkis$freshlyGenerated = true;
    }

    /**
     * Only the generator (and wrappers) use this constructor; storage reads pass their own tick schedulers.
     *
     * @param pos            chunk position
     * @param upgradeData    upgrade data
     * @param world          height limits
     * @param palettesFactory palette factory
     * @param blendingData   blending data, or {@code null}
     * @param ci             callback info
     */
    @Inject(method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V", at = @At("RETURN"))
    private void chunkis$markGenerated(final ChunkPos pos, final UpgradeData upgradeData,
            final LevelHeightAccessor world, final PalettedContainerFactory palettesFactory, final BlendingData blendingData,
            final CallbackInfo ci) {
        // A wrapper around a loaded world chunk passes through this constructor too.
        this.chunkis$freshlyGenerated = !((Object) this instanceof ImposterProtoChunk);
    }
}
