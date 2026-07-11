package io.liparakis.chunkis.world.tracking.load;

import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * Starts a background load for a chunk already confirmed to exist in CIS storage.
 */
public final class CisLoadPrefetcher {

    /**
     * Prefetches a 5x5 chunk window around the entering player.
     */
    private static final int INITIAL_PREFETCH_RADIUS = 2;

    /**
     * Performs cis load prefetcher.
     */
    private CisLoadPrefetcher() {
        throw new AssertionError("Utility class");
    }

    /**
     * Starts a speculative load for a confirmed CIS entry.
     *
     * @param world     owning world
     * @param requested requested cold-load chunk
     */
    public static void observeColdLoad(final ServerWorld world, final ChunkPos requested) {
        FabricCisStorageHelper.prefetch(
                world,
                requested,
                ChunkTraceStore.nextOperationId("prefetch")
        );
    }

    /**
     * Warms nearby CIS entries after a player enters the server without loading
     * additional Minecraft chunks.
     *
     * @param player entering player
     */
    public static void prefetchAroundPlayer(final ServerPlayerEntity player) {
        final ServerWorld world = player.getEntityWorld();
        final ChunkPos center = new ChunkPos(player.getBlockPos());
        for (int dx = -INITIAL_PREFETCH_RADIUS; dx <= INITIAL_PREFETCH_RADIUS; dx++) {
            for (int dz = -INITIAL_PREFETCH_RADIUS; dz <= INITIAL_PREFETCH_RADIUS; dz++) {
                observeColdLoad(world, new ChunkPos(center.x + dx, center.z + dz));
            }
        }
    }
}
