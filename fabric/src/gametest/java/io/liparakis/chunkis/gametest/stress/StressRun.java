package io.liparakis.chunkis.gametest.stress;

import net.minecraft.world.chunk.WorldChunk;

/**
 * A running stress scenario, as the shared tick and chunk hooks of {@link StressBenchGameTest} see it.
 */
interface StressRun {

    /**
     * Returns the metrics the tick hooks feed.
     *
     * @return metrics
     */
    StressMetrics metrics();

    /**
     * Called when a chunk finishes loading.
     *
     * @param loaded the chunk
     */
    void onChunkLoad(WorldChunk loaded);

    /**
     * Called when a chunk unloads.
     *
     * @param unloaded the chunk
     */
    void onChunkUnload(WorldChunk unloaded);

    /**
     * Advances the scenario by one server tick.
     *
     * @return true once the scenario finished and wrote its report
     */
    boolean tick();
}
