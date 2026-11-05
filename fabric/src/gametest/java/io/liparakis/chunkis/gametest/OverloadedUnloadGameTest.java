package io.liparakis.chunkis.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

/**
 * Chunk unloads on a server with no tick time left.
 */
public final class OverloadedUnloadGameTest {

    /**
     * Side of the square of chunks the test unloads.
     */
    private static final int SIDE = 10;

    /**
     * Ticks the exhausted budget lasts at most, so a failing test cannot stall every other test's unloads.
     */
    private static final int NO_BUDGET_TICKS = 300;

    /**
     * Server tick until which the unload loop runs with no budget; 0 when off.
     */
    private static volatile int noBudgetUntil;

    /**
     * Current server tick, as the mixin checks it.
     */
    private static volatile int currentTick;

    /**
     * Reports whether the unload loop should run with no tick budget now.
     *
     * @return true while a test simulates an overloaded server
     */
    public static boolean noBudget() {
        return currentTick < noBudgetUntil;
    }

    /**
     * Unloads a square of chunks while the unload loop has no tick time at all, as on a server that cannot keep
     * up, and expects every one of them to unload anyway, well before the simulated overload ends: a backlog
     * below the forced-unload limit must still drain.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 400)
    public void unloadsDrainWithNoTickBudget(final TestContext context) {
        // The End, which Chunkis owns in game tests: the Overworld test world is not authoritative, and there
        // vanilla's own unload limits apply.
        final ServerWorld world = context.getWorld().getServer().getWorld(net.minecraft.world.World.END);
        io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder
                .markAuthoritative(world.getRegistryKey());
        final List<ChunkPos> chunks = new ArrayList<>();
        for (int i = 0; i < SIDE * SIDE; i++) {
            chunks.add(new ChunkPos(-6100 + i % SIDE, 6100 + i / SIDE));
        }
        final AtomicInteger unloaded = new AtomicInteger();
        final List<Integer> unloadTicks = java.util.Collections.synchronizedList(new ArrayList<>());
        final int[] startTick = {0};
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, chunk) -> {
            if (unloadWorld == world && chunks.contains(chunk.getPos())) {
                unloaded.incrementAndGet();
                unloadTicks.add(world.getServer().getTicks() - startTick[0]);
            }
        });
        chunks.forEach(pos -> world.setChunkForced(pos.x, pos.z, true));
        chunks.forEach(pos -> world.getChunk(pos.x, pos.z));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    currentTick = world.getServer().getTicks();
                    startTick[0] = currentTick;
                    noBudgetUntil = currentTick + NO_BUDGET_TICKS;
                    chunks.forEach(pos -> world.setChunkForced(pos.x, pos.z, false));
                })
                .createAndAdd(() -> {
                    final int seen = unloaded.get();
                    currentTick = world.getServer().getTicks();
                    if (!noBudget()) {
                        throw context.createError(Text.literal("Only " + seen + " of " + chunks.size()
                                + " chunks unloaded in " + NO_BUDGET_TICKS + " ticks with no tick budget; unload ticks "
                                + unloadTicks));
                    }
                    context.assertTrue(unloaded.get() >= chunks.size(), Text.literal("Waiting for "
                            + (chunks.size() - unloaded.get()) + " chunks to unload with no tick budget."));
                    noBudgetUntil = 0;
                })
                .completeIfSuccessful();
    }
}
