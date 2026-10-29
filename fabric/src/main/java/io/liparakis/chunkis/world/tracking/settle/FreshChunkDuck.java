package io.liparakis.chunkis.world.tracking.settle;

/**
 * Marks a proto or world chunk that was generated in this session rather than read from storage; see
 * {@link GenerationSettling}.
 */
public interface FreshChunkDuck {

    /**
     * Returns whether the chunk was generated in this session.
     *
     * @return {@code true} for a freshly generated chunk
     */
    boolean chunkis$isFreshlyGenerated();

    /**
     * Marks the chunk as freshly generated.
     */
    void chunkis$markFreshlyGenerated();
}
