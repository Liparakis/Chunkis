package io.liparakis.chunkis.api;

import io.liparakis.chunkis.world.ChunkMutationTrackingScope;

/**
 * Exposes per-chunk mutation tracking suppression state.
 */
public interface ChunkisMutationGuardDuck {

    ChunkMutationTrackingScope chunkis$getMutationTrackingScope();
}
