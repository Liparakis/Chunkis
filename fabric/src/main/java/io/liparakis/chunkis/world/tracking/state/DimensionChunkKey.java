package io.liparakis.chunkis.world.tracking.state;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import java.util.Objects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * Identifies a chunk within a specific world dimension.
 */
public final class DimensionChunkKey {

    /**
     * Owning dimension world key.
     */
    private final RegistryKey<World> dimension;

    /**
     * Packed coordinates identifier key.
     */
    private final long chunkKey;

    /**
     * Precomputed cached hash code.
     */
    private final int hash;

    /**
     * Private constructor initializing key fields.
     *
     * @param dimension owning dimension world key
     * @param chunkKey  packed coordinates identifier key
     */
    private DimensionChunkKey(final RegistryKey<World> dimension, final long chunkKey) {
        this.dimension = dimension;
        this.chunkKey = chunkKey;
        this.hash = 31 * dimension.hashCode() + Long.hashCode(chunkKey);
    }

    /**
     * Resolves a key mapping from dimension and ChunkPos.
     *
     * @param dimension owning dimension world key
     * @param pos       chunk coordinates
     * @return resolved DimensionChunkKey
     */
    public static DimensionChunkKey of(final RegistryKey<World> dimension, final ChunkPos pos) {
        return of(dimension, pos.x, pos.z);
    }

    /**
     * Resolves a key mapping from dimension and coordinate values.
     *
     * @param dimension owning dimension world key
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @return resolved DimensionChunkKey
     */
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

    /**
     * Bit packs X and Z keys into a single long coordinate index.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return packed long coordinate
     */
    private static long packChunkKey(final int chunkX, final int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    /**
     * Returns the dimension registry key.
     *
     * @return registry key
     */
    public RegistryKey<World> dimension() {
        return dimension;
    }

    /**
     * Resolves and constructs target ChunkPos elements.
     *
     * @return ChunkPos mapping
     */
    public ChunkPos chunkPos() {
        return new ChunkPos(chunkX(), chunkZ());
    }

    /**
     * Resolves and constructs debug chunk keys.
     *
     * @return DebugChunkKey mapping
     */
    public DebugChunkKey debugChunkKey() {
        return new DebugChunkKey(chunkX(), chunkZ());
    }

    /**
     * Resolves world-space X coordinate of chunk boundary start blocks.
     *
     * @return block coordinate X start offset
     */
    public int chunkStartX() {
        return chunkX() << 4;
    }

    /**
     * Resolves world-space Z coordinate of chunk boundary start blocks.
     *
     * @return block coordinate Z start offset
     */
    public int chunkStartZ() {
        return chunkZ() << 4;
    }

    /**
     * Resolves chunk X coordinate mapping.
     *
     * @return chunk X key
     */
    public int chunkX() {
        return (int) chunkKey;
    }

    /**
     * Resolves chunk Z coordinate mapping.
     *
     * @return chunk Z key
     */
    public int chunkZ() {
        return (int) (chunkKey >>> 32);
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return hash;
    }
}
