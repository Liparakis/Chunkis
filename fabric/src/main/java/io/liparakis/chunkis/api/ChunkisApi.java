package io.liparakis.chunkis.api;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.io.CisStorage;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.world.chunk.Chunk;

/**
 * Public read-oriented API for querying Chunkis runtime state.
 *
 * <p>The API exposes the world-local {@link CisStorage} instance and the delta
 * currently attached to a chunk. It does not bypass Chunkis' normal save/load
 * pipeline, and it does not grant ownership of the returned objects to the
 * caller.</p>
 *
 * <p><b>Threading:</b> call from the normal server thread. The returned storage
 * and delta objects participate in live runtime state and are not documented as
 * safe for arbitrary cross-thread mutation.</p>
 */
public interface ChunkisApi {

    /**
     * Gets the singleton instance of the Chunkis API.
     *
     * @return the API instance
     * @throws IllegalStateException if the API is not yet initialized
     */
    static ChunkisApi getInstance() {
        if (Holder.instance == null) {
            throw new IllegalStateException("ChunkisApi is not initialized yet.");
        }
        return Holder.instance;
    }

    /**
     * Sets the singleton instance of the Chunkis API.
     *
     * @param instance the API instance to set
     */
    static void setInstance(ChunkisApi instance) {
        Holder.instance = instance;
    }

    /**
     * Returns the shared CIS storage instance for one server world.
     *
     * <p>The storage is world-scoped and owned by Chunkis. Callers must not
     * close it.</p>
     *
     * @param world world whose Chunkis storage should be returned
     * @return shared storage instance for {@code world}
     * @throws NullPointerException if {@code world} is {@code null}
     */
    CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage(ServerWorld world);

    /**
     * Returns the delta currently attached to a chunk, if any.
     *
     * <p>The returned delta is the live attached runtime object, not a defensive
     * copy. Callers must treat it as Chunkis-owned state.</p>
     *
     * @param chunk chunk to inspect
     * @return attached delta, or empty when the chunk does not expose Chunkis state
     * @throws NullPointerException if {@code chunk} is {@code null}
     */
    Optional<ChunkDelta<BlockState, NbtCompound>> getDelta(Chunk chunk);

    /**
     * Returns whether the chunk currently carries a non-empty attached delta.
     *
     * <p>This is a shape check, not a persistence guarantee. A chunk with stored
     * anchors but no replay payload can still be meaningful to Chunkis even if
     * this method returns {@code false}.</p>
     *
     * @param chunk chunk to inspect
     * @return {@code true} when the attached delta exists and is non-empty
     * @throws NullPointerException if {@code chunk} is {@code null}
     */
    boolean hasChunkisData(Chunk chunk);

    class Holder {

        /**
         * Stores instance.
         */
        private static ChunkisApi instance;
    }
}
