package io.liparakis.chunkis.api.impl;

import io.liparakis.chunkis.api.ChunkisApi;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Internal implementation of the {@link ChunkisApi} interface.
 *
 * <p>
 * Implemented as a singleton. Construction is private; callers obtain the
 * instance via {@link #getInstance()}.
 *
 * <p>
 * Chunk references passed into this class are never retained beyond the
 * scope of each method call.
 */
public final class ChunkisApiImpl implements ChunkisApi {

    /**
     * Stores instance.
     */
    private static final ChunkisApiImpl INSTANCE = new ChunkisApiImpl();

    /**
     * Performs chunkis api impl.
     */
    private ChunkisApiImpl() {
    }

    /**
     * Returns the singleton instance of {@link ChunkisApiImpl}.
     *
     * @return the shared instance
     */
    public static ChunkisApiImpl getInstance() {
        return INSTANCE;
    }

    /**
     * Returns true if the given chunk implements the {@link ChunkisDeltaDuck}
     * interface,
     * meaning it was mixed in and can carry a {@link ChunkDelta}.
     *
     * @param chunk the chunk to test
     * @return true if the chunk is a ChunkisDeltaDuck
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isChunkisDuck(final ChunkAccess chunk) {
        return chunk instanceof ChunkisDeltaDuck;
    }

    /**
     * Returns true if the given delta is non-null and contains at least one change.
     *
     * @param delta the delta to test, may be null
     * @return true if the delta is present and non-empty
     */
    private static boolean isNonEmptyDelta(final ChunkDelta<?, ?> delta) {
        return delta != null && !delta.isEmpty();
    }

    /**
     * Casts the given chunk to {@link ChunkisDeltaDuck}.
     *
     * <p>
     * Only call after confirming {@link #isChunkisDuck(ChunkAccess)} returns true.
     *
     * @param chunk the chunk to cast
     * @return the chunk as a ChunkisDeltaDuck
     */
    private static ChunkisDeltaDuck asDuck(final ChunkAccess chunk) {
        return (ChunkisDeltaDuck) chunk;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CisStorage<Block, BlockState, Property<?>, CompoundTag> getStorage(final ServerLevel world) {
        return FabricCisStorageHelper.getStorage(world);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The cast to {@code ChunkDelta<BlockState, NbtCompound>} is safe because
     * all deltas attached to chunks in the Fabric environment are constructed
     * with exactly these type parameters.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Optional<ChunkDelta<BlockState, CompoundTag>> getDelta(final ChunkAccess chunk) {
        if (!isChunkisDuck(chunk)) {
            return Optional.empty();
        }

        final ChunkDelta<BlockState, CompoundTag> rawDelta = (ChunkDelta<BlockState, CompoundTag>) asDuck(chunk)
                .chunkis$getDelta();
        if (rawDelta == null) {
            return Optional.empty();
        }

        return Optional.of(rawDelta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasChunkisData(final ChunkAccess chunk) {
        if (!isChunkisDuck(chunk)) {
            return false;
        }

        final ChunkDelta<?, ?> delta = asDuck(chunk).chunkis$getDelta();
        return isNonEmptyDelta(delta);
    }
}


