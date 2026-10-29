package io.liparakis.chunkis.world.restoration.core;

import it.unimi.dsi.fastutil.longs.LongList;

/**
 * Implemented on {@code ServerLightingProvider} by a mixin: queues many light checks of one chunk as a
 * single light-engine task instead of one task per block.
 */
public interface BatchedLightChecks {

    /**
     * Queues a light check at every position, with the same stage and ordering vanilla uses for
     * {@code checkBlock}.
     *
     * @param chunkX    chunk X the positions belong to
     * @param chunkZ    chunk Z the positions belong to
     * @param positions packed {@code BlockPos} longs; not modified or retained after queueing
     */
    void chunkis$checkBlocks(int chunkX, int chunkZ, LongList positions);
}
