package io.liparakis.chunkis.api;

import io.liparakis.chunkis.core.ChunkDelta;

/**
 * Interface that allows accessing the {@link ChunkDelta} associated with a
 * world chunk.
 */
public interface ChunkisDeltaDuck {

    ChunkDelta<?, ?> chunkis$getDelta();

    void chunkis$setDelta(ChunkDelta<?, ?> delta);

    String chunkis$getRestoreOperationId();

    void chunkis$setRestoreOperationId(String operationId);

    boolean chunkis$wasRestoreLoadedFromStorage();

    void chunkis$setRestoreLoadedFromStorage(boolean restoreLoadedFromStorage);
}
