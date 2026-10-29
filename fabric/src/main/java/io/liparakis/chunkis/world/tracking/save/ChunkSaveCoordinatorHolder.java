package io.liparakis.chunkis.world.tracking.save;

/**
 * Exposes the {@link ChunkSaveCoordinator} of a world's chunk loading manager, so save paths that bypass vanilla's
 * {@code save(Chunk)} (another mod's chunk system) can still route chunks through Chunkis.
 */
public interface ChunkSaveCoordinatorHolder {

    /**
     * Returns the world's save coordinator, creating it on first use.
     *
     * @return the coordinator
     */
    ChunkSaveCoordinator chunkis$saveCoordinator();
}
