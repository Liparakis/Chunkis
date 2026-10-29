package io.liparakis.chunkis.world.tracking.state;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Non-blocking lookup of a fully loaded chunk.
 *
 * <p>On the server thread, vanilla's {@code getWorldChunk(x, z, false)} does not return {@code null}
 * for a chunk that is still loading: it runs the main-thread task queue until that chunk finishes,
 * which pulls other chunks' loads and restores into the current tick. Called every tick, that turns
 * asynchronous chunk loading into tick spikes.</p>
 */
public final class LoadedChunks {

    /**
     * Static holder; not instantiable.
     */
    private LoadedChunks() {
    }

    /**
     * Returns the chunk if it is loaded and accessible right now, without waiting for a load in progress.
     * Off the server thread the holder map cannot be read, so vanilla's lookup is used.
     *
     * @param world  world
     * @param chunkX chunk X
     * @param chunkZ chunk Z
     * @return the accessible chunk, or {@code null}
     */
    public static WorldChunk getIfAccessible(final ServerWorld world, final int chunkX, final int chunkZ) {
        if (!world.getServer().isOnThread()) {
            return world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
        }
        final ChunkHolder holder = world.getChunkManager().chunkLoadingManager
                .getCurrentChunkHolder(ChunkPos.toLong(chunkX, chunkZ));
        return holder == null ? null : holder.getAccessibleFuture()
                .getNow(ChunkHolder.UNLOADED_WORLD_CHUNK)
                .orElse(null);
    }
}
