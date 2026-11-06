package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.world.restoration.core.BatchedLightChecks;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Adds {@link BatchedLightChecks} to the server lighting provider. A restore can change thousands of
 * blocks at once; vanilla's {@code checkBlock} hands each one to the chunk task scheduler separately.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ServerLightingProviderMixin extends LevelLightEngine implements BatchedLightChecks {

    /**
     * Required by the superclass; never called.
     *
     * @param chunkProvider unused
     * @param blockLight    unused
     * @param skyLight      unused
     */
    private ServerLightingProviderMixin(final LightChunkGetter chunkProvider, final boolean blockLight,
            final boolean skyLight) {
        super(chunkProvider, blockLight, skyLight);
    }

    /**
     * Vanilla's private stage-aware task queue.
     *
     * @param x     chunk X
     * @param z     chunk Z
     * @param stage light-engine stage
     * @param task  work to run on the light thread
     */
    @Shadow
    private void addTask(final int x, final int z, final ThreadedLevelLightEngine.TaskType stage, final Runnable task) {
    }

    @Override
    public void chunkis$checkBlocks(final int chunkX, final int chunkZ, final LongList positions) {
        if (positions.isEmpty()) {
            return;
        }
        final long[] packed = positions.toLongArray();
        // Same stage as vanilla's checkBlock, but one task for the whole batch.
        addTask(chunkX, chunkZ, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
            final LayerLightEventListener blockLight = getLayerListener(LightLayer.BLOCK);
            final LayerLightEventListener skyLight = getLayerListener(LightLayer.SKY);
            for (final long position : packed) {
                final BlockPos pos = BlockPos.of(position);
                final SectionPos section = SectionPos.of(pos);
                // A chunk unload's light reset is queued at a higher priority and can run first; checking
                // a position whose light section is gone crashes the light engine, and there is nothing
                // left to light there anyway.
                if (blockLight.getDataLayerData(section) != null) {
                    blockLight.checkBlock(pos);
                }
                if (skyLight.getDataLayerData(section) != null) {
                    skyLight.checkBlock(pos);
                }
            }
        });
    }
}
