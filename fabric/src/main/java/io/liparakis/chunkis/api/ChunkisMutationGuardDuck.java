package io.liparakis.chunkis.api;

import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;

/**
 * Exposes per-chunk mutation tracking suppression state.
 */
public interface ChunkisMutationGuardDuck {

    ChunkMutationTrackingScope chunkis$getMutationTrackingScope();
}

