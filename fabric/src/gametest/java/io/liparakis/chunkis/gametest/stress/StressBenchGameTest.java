package io.liparakis.chunkis.gametest.stress;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;

/**
 * Stress benchmark entry point. Skipped unless the JVM runs with {@code -Dchunkis.stress=true}, so the
 * normal gametest suite stays fast.
 *
 * <p>Run it alone with
 * {@code gradlew :fabric:runGameTest -PchunkisStress=true -PgametestFilter=chunkis-gametest:stress_bench_game_test_round_trip_reload_and_edits};
 * the report lands in {@code fabric/build/run/gametest/chunkis-stress/}.</p>
 */
@SuppressWarnings("unused")
public final class StressBenchGameTest {

    /**
     * Scenario currently running, or {@code null}; read by the global event hooks.
     */
    private static StressRun active;

    /**
     * Whether the global event hooks are registered.
     */
    private static boolean hooksRegistered;

    /**
     * Whether the server should wait for real tick boundaries; read by {@code TestServerMixin}.
     */
    private static volatile boolean pacingTicks;

    /**
     * Whether the benchmark measures vanilla storage; read by {@code VanillaStorageModeMixin}.
     */
    private static final boolean VANILLA_STORAGE = Boolean.getBoolean("chunkis.stress.vanilla");

    /**
     * Runs the round-trip reload and edit scenario.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 72_000)
    public void roundTripReloadAndEdits(final TestContext context) {
        if (!Boolean.getBoolean("chunkis.stress")) {
            context.complete();
            return;
        }
        registerHooks();
        final StressScenario scenario = new StressScenario(context);
        active = scenario;
        scenario.begin();
        context.runAtEveryTick(() -> {
            if (active == scenario && scenario.tick()) {
                active = null;
            }
        });
    }

    /**
     * Runs the flight scenario: a player flying out over stored terrain and back.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 72_000)
    public void flightOutAndBack(final TestContext context) {
        if (!Boolean.getBoolean("chunkis.stress")) {
            context.complete();
            return;
        }
        registerHooks();
        final FlightScenario scenario = new FlightScenario(context);
        active = scenario;
        scenario.begin();
        context.runAtEveryTick(() -> {
            if (active == scenario && scenario.tick()) {
                active = null;
            }
        });
    }

    /**
     * Reports whether the server should keep a real tick rate instead of running ticks back to back.
     *
     * @return true while a stress scenario with a tick rate runs
     */
    public static boolean isPacingTicks() {
        return pacingTicks;
    }

    /**
     * Reports whether chunks must stay on vanilla storage, so the benchmark measures vanilla instead of Chunkis.
     *
     * @return true when running with {@code -Dchunkis.stress.vanilla=true} while a scenario runs
     */
    public static boolean isVanillaStorageMode() {
        return VANILLA_STORAGE && active != null;
    }

    /**
     * Turns real tick pacing on or off.
     *
     * @param pacing whether to pace ticks
     */
    static void setPacingTicks(final boolean pacing) {
        pacingTicks = pacing;
    }

    /**
     * Registers tick timing and chunk load/unload hooks once per JVM.
     */
    private static void registerHooks() {
        if (hooksRegistered) {
            return;
        }
        hooksRegistered = true;
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (active != null) {
                active.metrics().onTickStart();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (active != null) {
                active.metrics().onTickEnd();
            }
        });
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (active != null) {
                active.onChunkLoad(chunk);
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (active != null) {
                active.onChunkUnload(chunk);
            }
        });
    }
}
