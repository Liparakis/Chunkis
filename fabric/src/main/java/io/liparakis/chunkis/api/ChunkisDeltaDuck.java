package io.liparakis.chunkis.api;

import io.liparakis.chunkis.core.ChunkDelta;

/**
 * Interface that allows accessing the {@link ChunkDelta} associated with a
 * world chunk.
 */
public interface ChunkisDeltaDuck {

    /**
     * Retrieves the attached ChunkDelta.
     *
     * @return the current block/entity chunk delta, or null
     */
    ChunkDelta<?, ?> chunkis$getDelta();

    /**
     * Binds a new ChunkDelta instance to the chunk.
     *
     * @param delta the delta instance to associate
     */
    void chunkis$setDelta(ChunkDelta<?, ?> delta);

    /**
     * Resolves the active restore session operation ID.
     *
     * @return active operation ID string, or null
     */
    String chunkis$getRestoreOperationId();

    /**
     * Sets the active restore session operation ID.
     *
     * @param operationId active operation ID string
     */
    void chunkis$setRestoreOperationId(String operationId);

    /**
     * Checks if the active restore state loaded coordinates from storage.
     *
     * @return true if loaded from storage
     */
    boolean chunkis$wasRestoreLoadedFromStorage();

    /**
     * Sets whether the active restore state loaded coordinates from storage.
     *
     * @param restoreLoadedFromStorage true if loaded from storage
     */
    void chunkis$setRestoreLoadedFromStorage(boolean restoreLoadedFromStorage);
}
