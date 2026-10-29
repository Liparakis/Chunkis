package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.world.restoration.core.BatchedLightChecks;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.ChunkLightingView;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Adds {@link BatchedLightChecks} to the server lighting provider. A restore can change thousands of
 * blocks at once; vanilla's {@code checkBlock} hands each one to the chunk task scheduler separately.
 */
@Mixin(ServerLightingProvider.class)
public abstract class ServerLightingProviderMixin extends LightingProvider implements BatchedLightChecks {

    /**
     * Required by the superclass; never called.
     *
     * @param chunkProvider unused
     * @param blockLight    unused
     * @param skyLight      unused
     */
    private ServerLightingProviderMixin(final ChunkProvider chunkProvider, final boolean blockLight,
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
    private void enqueue(final int x, final int z, final ServerLightingProvider.Stage stage, final Runnable task) {
    }

    @Override
    public void chunkis$checkBlocks(final int chunkX, final int chunkZ, final LongList positions) {
        if (positions.isEmpty()) {
            return;
        }
        final long[] packed = positions.toLongArray();
        // Same stage as vanilla's checkBlock, but one task for the whole batch.
        enqueue(chunkX, chunkZ, ServerLightingProvider.Stage.PRE_UPDATE, () -> {
            final ChunkLightingView blockLight = get(LightType.BLOCK);
            final ChunkLightingView skyLight = get(LightType.SKY);
            for (final long position : packed) {
                final BlockPos pos = BlockPos.fromLong(position);
                final ChunkSectionPos section = ChunkSectionPos.from(pos);
                // A chunk unload's light reset is queued at a higher priority and can run first; checking
                // a position whose light section is gone crashes the light engine, and there is nothing
                // left to light there anyway.
                if (blockLight.getLightSection(section) != null) {
                    blockLight.checkBlock(pos);
                }
                if (skyLight.getLightSection(section) != null) {
                    skyLight.checkBlock(pos);
                }
            }
        });
    }
}
