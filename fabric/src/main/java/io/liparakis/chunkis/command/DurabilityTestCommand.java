package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registers and handles the {@code /durability_test} and {@code /durability_test_stop} commands.
 *
 * <p>Rapidly teleports the player between two positions to stress the chunk load/save pipeline
 * and verify fix durability under high-frequency transitions.
 *
 * <p>Only one test may run at a time. Starting a new test while one is running stops the
 * previous one automatically. Requires {@link PermissionLevel#GAMEMASTERS} to execute.
 *
 * <h2>Threading model</h2>
 * <p>A dedicated daemon {@link ScheduledExecutorService} fires at each {@code delayMs} tick.
 * Each tick dispatches exactly one unit of work to the server main thread via
 * {@code server.execute()}. The {@link #executorRef} and {@link #runIdRef} atomics are the
 * coordination point between the background executor thread and the server main thread: a
 * {@link AtomicReference#compareAndSet CAS} on {@code executorRef} determines which code path
 * "wins" the right to shut down the executor and emit the final trace event.
 */
public final class DurabilityTestCommand {

    /**
     * Trace source label identifying this command in the {@link ChunkTraceStore}.
     * All events emitted by this command share this source string.
     */
    private static final String SOURCE = "DurabilityTestCommand";

    /**
     * The executor driving the currently-running test, or {@code null} if no test is active.
     * Written on the server main thread; {@link AtomicReference} is used so the background
     * executor thread can safely read it as part of the CAS-based shutdown protocol.
     */
    private static final AtomicReference<ScheduledExecutorService> executorRef = new AtomicReference<>();

    /**
     * The operation ID (run ID) for the currently-running test, or {@code null} if no test
     * is active. Always set and cleared in tandem with {@link #executorRef}.
     */
    private static final AtomicReference<String> runIdRef = new AtomicReference<>();

    /** Minimum permitted interval between teleport ticks. Enforced via clamping in {@link #runTest}. */
    private static final int MIN_DELAY_MS = 10;

    private DurabilityTestCommand() {
        throw new AssertionError("Utility class");
    }

    /**
     * Registers the {@code /durability_test} and {@code /durability_test_stop} commands.
     *
     * <p>{@code /durability_test pos1 pos2 count delayMs} starts a test that teleports the
     * executing player between {@code pos1} and {@code pos2}, {@code count} times, with
     * {@code delayMs} milliseconds between each teleport (minimum {@value #MIN_DELAY_MS} ms).
     *
     * <p>{@code /durability_test_stop} stops any currently-running test.
     *
     * @param dispatcher the Brigadier command dispatcher to register into
     */
    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("durability_test")
                        .requires(source -> source.getPermissions()
                                .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .then(CommandManager.argument("pos1", Vec3ArgumentType.vec3())
                                .then(CommandManager.argument("pos2", Vec3ArgumentType.vec3())
                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1))
                                                // Note: integer(1) accepts values < MIN_DELAY_MS; the floor
                                                // is enforced by Math.max in runTest instead.
                                                .then(CommandManager.argument("delayMs", IntegerArgumentType.integer(1))
                                                        .executes(DurabilityTestCommand::runTest))))));

        dispatcher.register(
                CommandManager.literal("durability_test_stop")
                        .requires(source -> source.getPermissions()
                                .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .executes(DurabilityTestCommand::stopTest));
    }

    /**
     * Starts a new durability test. Any currently-running test is stopped first.
     *
     * <p>The test fires a scheduled tick every {@code delayMs} ms (clamped to
     * {@value #MIN_DELAY_MS} ms minimum). Each tick submits one teleport to the server main
     * thread. The test runs for exactly {@code count} teleports, alternating between
     * {@code pos1} and {@code pos2}.
     *
     * @return 1 on success, 0 if the source is not a player
     */
    private static int runTest(final CommandContext<ServerCommandSource> context) {
        final ServerCommandSource source = context.getSource();
        final ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }

        final Vec3d pos1 = Vec3ArgumentType.getVec3(context, "pos1");
        final Vec3d pos2 = Vec3ArgumentType.getVec3(context, "pos2");
        final int count = IntegerArgumentType.getInteger(context, "count");
        // Clamp to MIN_DELAY_MS: values below this cause the executor to fire faster than
        // the server can drain its task queue, which defeats the purpose of the test.
        final int delayMs = Math.max(MIN_DELAY_MS, IntegerArgumentType.getInteger(context, "delayMs"));

        stopInternal("replaced by a newer durability test");

        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "Chunkis-Durability-Test");
            thread.setDaemon(true);
            return thread;
        });
        final String runId = ChunkTraceStore.nextOperationId("durability");
        executorRef.set(executor);
        runIdRef.set(runId);

        // remaining starts at count and is decremented each server-thread tick.
        // Teleports fire while left >= 0 (i.e. exactly count times: count-1, count-2, ..., 0).
        // The tick where left == -1 is the termination tick no teleport, just cleanup.
        final AtomicInteger remaining = new AtomicInteger(count);

        // Guard flag: prevents a second server-thread task from being dispatched while the
        // previous one is still executing. Without this, a slow server main thread could
        // accumulate a backlog of teleport tasks that all fire in rapid succession once it
        // catches up, overwhelming the pipeline rather than stress-testing it evenly.
        final AtomicBoolean teleportQueued = new AtomicBoolean(false);

        traceStarted(count, delayMs, worldId(source), runId);
        source.sendFeedback(
                () -> Text.literal("[Chunkis] Starting durability test: " + count + " cycles at " + delayMs + "ms delay"),
                true
        );

        executor.scheduleAtFixedRate(
                () -> {
                    // Drop this tick if the previous server-thread dispatch hasn't completed yet.
                    if (!teleportQueued.compareAndSet(false, true)) {
                        return;
                    }
                    source.getServer().execute(() -> {
                        try {
                            final int left = remaining.decrementAndGet();

                            // left == -1 on the (count+1)th call: all count teleports are done.
                            if (left < 0) {
                                if (shutdownAndClear(executor, runId)) {
                                    trace(
                                            ChunkTraceEventType.DURABILITY_TEST_STOPPED,
                                            ChunkTraceSeverity.INFO,
                                            ChunkTraceReason.NONE,
                                            "durability test completed",
                                            worldId(source),
                                            null,
                                            runId
                                    );
                                }
                                source.sendFeedback(
                                        () -> Text.literal("[Chunkis] Durability test complete."),
                                        true
                                );
                                return;
                            }

                            if (player.isRemoved()) {
                                if (shutdownAndClear(executor, runId)) {
                                    traceFailed("player was removed during durability test", worldId(source), runId);
                                }
                                source.sendError(Text.literal("[Chunkis] Durability test failed: player was removed."));
                                return;
                            }

                            final ServerWorld world = player.getEntityWorld();
                            // Alternate between pos1 (even remaining) and pos2 (odd remaining).
                            final Vec3d target = (left % 2 == 0) ? pos1 : pos2;
                            trace(
                                    ChunkTraceEventType.DURABILITY_TELEPORT_EXECUTED,
                                    ChunkTraceSeverity.INFO,
                                    ChunkTraceReason.NONE,
                                    "queued durability teleport remaining=" + left,
                                    world.getRegistryKey().getValue().toString(),
                                    toChunkKey(target),
                                    runId
                            );
                            player.teleport(
                                    world,
                                    target.x,
                                    target.y,
                                    target.z,
                                    Set.of(),   // no relative movement flags absolute teleport
                                    player.getYaw(),
                                    player.getPitch(),
                                    false
                            );
                        } catch (final Exception exception) {
                            if (shutdownAndClear(executor, runId)) {
                                traceFailed(exception.getMessage(), worldId(source), runId);
                            }
                            source.sendError(
                                    Text.literal("[Chunkis] Durability test failed: " + exception.getMessage())
                            );
                        } finally {
                            // Reset the guard so the next executor tick can dispatch again.
                            teleportQueued.set(false);
                        }
                    });
                },
                0,
                delayMs,
                TimeUnit.MILLISECONDS
        );

        return 1;
    }

    /**
     * Handles {@code /durability_test_stop}. Stops the currently-running test and sends
     * feedback to the source, or sends an error if no test is running.
     *
     * @return always 1 (Brigadier convention)
     */
    private static int stopTest(final CommandContext<ServerCommandSource> context) {
        if (stopInternal("stopped manually")) {
            context.getSource().sendFeedback(() -> Text.literal("[Chunkis] Durability test stopped."), true);
        } else {
            context.getSource().sendError(Text.literal("No durability test is currently running."));
        }
        return 1;
    }

    /**
     * Stops the currently-running test, if any, by shutting down its executor and emitting a
     * {@code DURABILITY_TEST_STOPPED} trace event with {@code message} as the reason.
     *
     * <p>This method reads {@link #executorRef} and {@link #runIdRef} by value, then uses a CAS
     * on {@code executorRef} to claim the "shutdown" role. Only the caller that wins the CAS
     * emits the trace and returns {@code true}. This is the external variant of shutdown called
     * from outside the scheduled loop, where direct references to the captured executor are not
     * available. See also {@link #shutdownAndClear} for the internal variant.
     *
     * <p>{@code executor.shutdownNow()} is called before the CAS intentionally: if the CAS
     * subsequently fails (another caller already won), the shutdown call is still safe because
     * {@link ScheduledExecutorService#shutdownNow()} is idempotent.
     *
     * @param message trace message describing why the test was stopped
     * @return {@code true} if a running test was found and stopped, {@code false} if none was running
     */
    private static boolean stopInternal(final String message) {
        final ScheduledExecutorService executor = executorRef.get();
        final String runId = runIdRef.get();
        if (executor == null || runId == null) {
            return false;
        }

        // Shut down before the CAS: idempotent if the CAS later fails.
        executor.shutdownNow();
        if (!executorRef.compareAndSet(executor, null)) {
            return false;
        }

        runIdRef.compareAndSet(runId, null);
        trace(
                ChunkTraceEventType.DURABILITY_TEST_STOPPED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                message,
                null,
                null,
                runId
        );
        return true;
    }

    /**
     * Shuts down {@code executor} and clears the global refs, using a CAS on {@link #executorRef}
     * to claim the "shutdown" role. Returns {@code true} only to the caller that wins the CAS.
     *
     * <p>This is the internal variant of shutdown, called from within the scheduled loop where
     * the captured {@code executor} and {@code runId} locals are already available. Using them
     * directly avoids reading the atomics again and guards against a race where the refs are
     * updated by a concurrent {@link #stopInternal} call before this method reads them.
     *
     * <p>As with {@link #stopInternal}, {@code shutdownNow()} is called before the CAS because
     * it is idempotent and we want to ensure the executor is always halted when this method is
     * called, regardless of which caller wins.
     *
     * @param executor the executor captured at test-start time
     * @param runId    the run ID captured at test-start time
     * @return {@code true} if this caller won the CAS and should emit the terminal trace event
     */
    private static boolean shutdownAndClear(final ScheduledExecutorService executor, final String runId) {
        executor.shutdownNow();
        if (!executorRef.compareAndSet(executor, null)) {
            return false;
        }
        runIdRef.compareAndSet(runId, null);
        return true;
    }

    /**
     * Converts a world-space position to its enclosing chunk coordinate.
     * Uses arithmetic right-shift by 4 ({@code >> 4}), equivalent to {@code floor(x) / 16}.
     *
     * <p>Package-visible for unit testing.
     *
     * @param target the world-space position; only X and Z are used
     * @return the {@link DebugChunkKey} for the chunk containing {@code target}
     */
    static DebugChunkKey toChunkKey(final Vec3d target) {
        return new DebugChunkKey(MathHelper.floor(target.x) >> 4, MathHelper.floor(target.z) >> 4);
    }

    /**
     * Returns the registry key string of the world the command source is currently in.
     * Must only be called on the server main thread.
     *
     * @param source a command source with an associated world
     * @return e.g. {@code "minecraft:overworld"}
     */
    private static String worldId(final ServerCommandSource source) {
        return source.getWorld().getRegistryKey().getValue().toString();
    }

    /**
     * Emits a {@code DURABILITY_TEST_STARTED} trace event.
     * Package-visible for unit testing.
     *
     * @param count       total number of teleports scheduled
     * @param delayMs     clamped delay between teleports, in milliseconds
     * @param worldId     the world the test is running in
     * @param operationId the run ID for this test
     */
    static void traceStarted(final int count, final int delayMs,
                             final String worldId, final String operationId) {
        trace(
                ChunkTraceEventType.DURABILITY_TEST_STARTED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "starting durability test count=" + count + " delayMs=" + delayMs,
                worldId,
                null,
                operationId
        );
    }

    /**
     * Emits a {@code DURABILITY_TEST_FAILED} trace event.
     * Package-visible for unit testing.
     *
     * @param failureMessage human-readable description of the failure cause
     * @param worldId        the world the test was running in
     * @param operationId    the run ID for this test
     */
    static void traceFailed(final String failureMessage, final String worldId, final String operationId) {
        trace(
                ChunkTraceEventType.DURABILITY_TEST_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.IO_EXCEPTION,
                "durability test failed: " + failureMessage,
                worldId,
                null,
                operationId
        );
    }

    /**
     * Submits a trace event to {@link ChunkTraceStore} under the {@link ChunkisDebugDomain#CHUNK_LIFECYCLE}
     * domain. All durability test events share this domain and {@link #SOURCE} label.
     *
     * @param eventType   the event type to record
     * @param severity    severity level
     * @param reason      structured reason code
     * @param message     human-readable description
     * @param worldId     world context, or {@code null} if not applicable
     * @param chunkKey    chunk coordinate context, or {@code null} if not applicable
     * @param operationId the run ID tying this event to its test run
     */
    private static void trace(
            final ChunkTraceEventType eventType,
            final ChunkTraceSeverity severity,
            final ChunkTraceReason reason,
            final String message,
            final String worldId,
            final DebugChunkKey chunkKey,
            final String operationId
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                eventType,
                severity,
                reason,
                SOURCE,
                message,
                worldId,
                chunkKey,
                null,
                operationId,
                null,
                null
        );
    }
}