package io.liparakis.chunkis.api;

import io.liparakis.chunkis.core.ChunkDelta;

/**
 * Duck-typed attachment point for Chunkis per-chunk runtime state.
 *
 * <p>This interface is implemented by mixins on chunk classes. It is an
 * internal integration surface, not a stable external API.</p>
 *
 * <p><b>Ownership:</b> the attached {@link ChunkDelta} and restore flags are
 * owned by Chunkis. Callers may inspect them, but they should not replace or
 * mutate them casually outside the normal persistence pipeline.</p>
 */
public interface ChunkisDeltaDuck {

    /**
     * Returns the delta currently attached to the chunk.
     *
     * @return live attached delta, or {@code null} when none has been installed
     */
    ChunkDelta<?, ?> chunkis$getDelta();

    /**
     * Replaces the attached delta reference.
     *
     * @param delta new delta reference, or {@code null} to clear it
     */
    void chunkis$setDelta(ChunkDelta<?, ?> delta);

    /**
     * Returns the current restore operation id associated with this chunk.
     *
     * @return restore operation id, or {@code null} when no restore is active
     */
    String chunkis$getRestoreOperationId();

    /**
     * Stores the restore operation id currently associated with this chunk.
     *
     * @param operationId restore operation id, or {@code null} to clear it
     */
    void chunkis$setRestoreOperationId(String operationId);

    /**
     * Returns whether the current restore state originated from persisted storage.
     *
     * @return {@code true} when the attached restore state came from storage
     */
    boolean chunkis$wasRestoreLoadedFromStorage();

    /**
     * Records whether the current restore state originated from persisted storage.
     *
     * @param restoreLoadedFromStorage {@code true} when storage was the restore source
     */
    void chunkis$setRestoreLoadedFromStorage(boolean restoreLoadedFromStorage);
}
