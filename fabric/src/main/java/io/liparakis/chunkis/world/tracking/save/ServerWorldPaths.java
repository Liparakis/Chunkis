package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.mixin.accessor.MinecraftServerAccessor;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

/** Save-root lookup safe for chunk-loading workers as well as the server thread. */
public final class ServerWorldPaths {
    /** Utility class. */
    private ServerWorldPaths() {
    }

    /**
     * Resolves ROOT from the immutable directory descriptor. MinecraftServer.getWorldPath mutates a plain
     * HashMap on a cache miss; concurrent first loads across dimensions can otherwise fail during storage
     * initialization. This uses the same path resolution without that cache or any new shared state.
     */
    public static Path root(final MinecraftServer server) {
        return root(((MinecraftServerAccessor) server).chunkis$getStorageSource());
    }

    /** Resolves directly from the save access without reading or modifying its resource cache. */
    static Path root(final LevelStorageSource.LevelStorageAccess access) {
        return access.getLevelDirectory().resourcePath(LevelResource.ROOT);
    }
}
