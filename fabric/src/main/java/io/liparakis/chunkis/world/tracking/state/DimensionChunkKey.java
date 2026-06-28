package io.liparakis.chunkis.world.tracking.state;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.Objects;

/**
 * Identifies a chunk within a specific world dimension.
 */
public final class DimensionChunkKey {

    private final RegistryKey<World> dimension;
    private final long chunkKey;
    private final int hash;

    private DimensionChunkKey(final RegistryKey<World> dimension, final long chunkKey) {
        this.dimension = dimension;
        this.chunkKey = chunkKey;
        this.hash = 31 * dimension.hashCode() + Long.hashCode(chunkKey);
    }

    public static DimensionChunkKey of(final RegistryKey<World> dimension, final ChunkPos pos) {
        return of(dimension, pos.x, pos.z);
    }

    public static DimensionChunkKey of(
            final RegistryKey<World> dimension,
            final int chunkX,
            final int chunkZ
    ) {
        return new DimensionChunkKey(
                Objects.requireNonNull(dimension, "dimension"),
                packChunkKey(chunkX, chunkZ)
        );
    }

    public RegistryKey<World> dimension() {
        return dimension;
    }

    public ChunkPos chunkPos() {
        return new ChunkPos(chunkX(), chunkZ());
    }

    public DebugChunkKey debugChunkKey() {
        return new DebugChunkKey(chunkX(), chunkZ());
    }

    public int chunkStartX() {
        return chunkX() << 4;
    }

    public int chunkStartZ() {
        return chunkZ() << 4;
    }

    public int chunkX() {
        return (int) chunkKey;
    }

    public int chunkZ() {
        return (int) (chunkKey >>> 32);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DimensionChunkKey other)) {
            return false;
        }
        return chunkKey == other.chunkKey && dimension.equals(other.dimension);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    private static long packChunkKey(final int chunkX, final int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }
}
