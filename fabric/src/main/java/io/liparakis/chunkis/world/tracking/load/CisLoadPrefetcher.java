package io.liparakis.chunkis.world.tracking.load;

import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/** Starts a background load for a chunk already confirmed to exist in CIS storage. */
public final class CisLoadPrefetcher {

    private CisLoadPrefetcher() {
        throw new AssertionError("Utility class");
    }

    /**
     * Starts a speculative load for a confirmed CIS entry.
     *
     * @param world owning world
     * @param requested requested cold-load chunk
     */
    public static void observeColdLoad(final ServerWorld world, final ChunkPos requested) {
        FabricCisStorageHelper.prefetch(
                world,
                requested,
                ChunkTraceStore.nextOperationId("prefetch")
        );
    }
}
