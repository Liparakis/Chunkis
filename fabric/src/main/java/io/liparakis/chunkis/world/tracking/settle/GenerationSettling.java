package io.liparakis.chunkis.world.tracking.settle;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.world.tick.Tick;

/**
 * Tells world settling that replays from the seed apart from changes a player or an entity caused.
 *
 * <p>Right after a chunk is generated, vanilla settles it: post-processing fixes generated blocks, and the
 * scheduled ticks generation left behind make leaves recompute their distance, springs flow and magma build
 * bubble columns. Regenerating the chunk from the seed replays all of it, so a chunk Chunkis does not own yet must
 * not be claimed for it. Everything else (player and entity actions, random ticks, ticks of chunks loaded from
 * storage) keeps claiming, so no change a player caused is lost.</p>
 *
 * <p>Origin follows the ticks: each chunk tick scheduler keeps the positions of its generation ticks (seeded when
 * a freshly generated chunk becomes a world chunk). A tick scheduled while a generation tick or post-processing
 * runs is a generation tick as well; one scheduled by anything else takes the position back, since vanilla
 * merges duplicate ticks and the live cause must win. Runs on the server thread.</p>
 */
public final class GenerationSettling {

    /**
     * Nesting depth of running generation work on this thread.
     */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * Not instantiable.
     */
    private GenerationSettling() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns whether the current block changes come from generation settling.
     *
     * @return {@code true} inside a generation tick or a freshly generated chunk's post-processing
     */
    public static boolean isActive() {
        return DEPTH.get()[0] > 0;
    }

    /**
     * Runs work as generation settling when {@code generation} is set, else as it already is.
     *
     * @param generation whether the work replays from the seed
     * @param work       work to run
     */
    public static void run(final boolean generation, final Runnable work) {
        if (!generation) {
            work.run();
            return;
        }
        final int[] depth = DEPTH.get();
        depth[0]++;
        try {
            work.run();
        } finally {
            depth[0]--;
        }
    }

    /**
     * Takes a due scheduled tick's origin: whether it is a generation tick.
     *
     * @param scheduler scheduler of the tick's chunk, or {@code null} when the chunk is not loaded
     * @param pos       packed tick position
     * @return {@code true} when the tick should run as generation settling
     */
    public static boolean takeGenerationTick(final GenerationTickDuck scheduler, final long pos) {
        final LongSet generationTicks = scheduler == null ? null : scheduler.chunkis$generationTicks();
        return generationTicks != null && generationTicks.remove(pos);
    }

    /**
     * Records the origin of a newly scheduled tick.
     *
     * @param scheduler scheduler receiving the tick
     * @param pos       packed tick position
     */
    public static void onScheduled(final GenerationTickDuck scheduler, final long pos) {
        if (isActive()) {
            generationTicks(scheduler).add(pos);
            return;
        }
        final LongSet generationTicks = scheduler.chunkis$generationTicks();
        if (generationTicks != null) {
            generationTicks.remove(pos);
        }
    }

    /**
     * Marks every pending tick of a freshly generated chunk as a generation tick.
     *
     * @param scheduler scheduler of the new world chunk
     * @param ticks     its pending ticks
     */
    public static void tagGenerated(final GenerationTickDuck scheduler, final List<? extends Tick<?>> ticks) {
        if (ticks.isEmpty()) {
            return;
        }
        final LongSet generationTicks = generationTicks(scheduler);
        for (final Tick<?> tick : ticks) {
            generationTicks.add(tick.pos().asLong());
        }
    }

    /**
     * Returns a scheduler's generation-tick positions, creating the set on first use.
     *
     * @param scheduler tick scheduler
     * @return its positions
     */
    private static LongSet generationTicks(final GenerationTickDuck scheduler) {
        LongSet generationTicks = scheduler.chunkis$generationTicks();
        if (generationTicks == null) {
            generationTicks = new LongOpenHashSet();
            scheduler.chunkis$setGenerationTicks(generationTicks);
        }
        return generationTicks;
    }
}
