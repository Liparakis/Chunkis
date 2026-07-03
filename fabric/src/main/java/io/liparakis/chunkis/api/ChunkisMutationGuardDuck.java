package io.liparakis.chunkis.api;

import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;

/**
 * Exposes the per-chunk mutation-tracking suppression scope.
 *
 * <p>Chunkis uses this during restore and other non-player mutation paths so
 * replayed writes are not misclassified as fresh live edits.</p>
 */
public interface ChunkisMutationGuardDuck {

    /**
     * Returns the active mutation-tracking suppression scope for the chunk.
     *
     * @return current suppression scope, never transferring ownership to the caller
     */
    ChunkMutationTrackingScope chunkis$getMutationTrackingScope();
}

