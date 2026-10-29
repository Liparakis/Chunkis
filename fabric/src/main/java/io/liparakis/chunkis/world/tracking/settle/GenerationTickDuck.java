package io.liparakis.chunkis.world.tracking.settle;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Generation-tick positions a chunk tick scheduler carries; see {@link GenerationSettling}.
 */
public interface GenerationTickDuck {

    /**
     * Returns the packed positions of this scheduler's pending generation ticks.
     *
     * @return the positions, or {@code null} before the first one
     */
    LongSet chunkis$generationTicks();

    /**
     * Sets the generation-tick positions.
     *
     * @param generationTicks positions to keep
     */
    void chunkis$setGenerationTicks(LongSet generationTicks);
}
