package io.liparakis.chunkis.world.tracking.state;

import io.liparakis.chunkis.mixin.accessor.ServerChunkLoadingManagerAccessor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

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
    public static LevelChunk getIfAccessible(final ServerLevel world, final int chunkX, final int chunkZ) {
        if (!world.getServer().isSameThread()) {
            return world.getChunkSource().getChunk(chunkX, chunkZ, false);
        }
        final ChunkHolder holder = world.getChunkSource().chunkMap
                .getUpdatingChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
        return holder == null ? null : holder.getFullChunkFuture()
                .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
                .orElse(null);
    }

    /**
     * Returns the chunk while a holder still holds it as a full chunk: after it dropped below full level and is
     * no longer accessible, and after it left the loaded set until its unload save ran. Vanilla can save the
     * chunk's entities in either window, before the chunk itself saves. Off the server thread the holder maps
     * cannot be read, so this falls back to {@link #getIfAccessible}.
     *
     * @param world  world
     * @param chunkX chunk X
     * @param chunkZ chunk Z
     * @return the held chunk, or {@code null} once its unload save ran or before it is full
     */
    public static LevelChunk getHeld(final ServerLevel world, final int chunkX, final int chunkZ) {
        if (!world.getServer().isSameThread()) {
            return getIfAccessible(world, chunkX, chunkZ);
        }
        final long pos = ChunkPos.pack(chunkX, chunkZ);
        final ChunkMap manager = world.getChunkSource().chunkMap;
        ChunkHolder holder = manager.getUpdatingChunkIfPresent(pos);
        if (holder == null) {
            holder = ((ServerChunkLoadingManagerAccessor) manager).chunkis$getChunksToUnload()
                    .get(pos);
        }
        return holder != null && holder.getLatestChunk() instanceof LevelChunk chunk ? chunk : null;
    }
}
