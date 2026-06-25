package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registers and handles the {@code /durability_test} and {@code /durability_test_stop} commands.
 *
 * <p>Rapidly teleports the player between two positions to stress the chunk
 * load/save pipeline and verify fix durability under high-frequency transitions.
 *
 * <p>Requires {@link PermissionLevel#GAMEMASTERS} to execute.
 */
public final class DurabilityTestCommand {

    private static final String SOURCE = "DurabilityTestCommand";
    private static final AtomicReference<ScheduledExecutorService> executorRef = new AtomicReference<>();
    private static final AtomicReference<String> runIdRef = new AtomicReference<>();

    private DurabilityTestCommand() {
        throw new AssertionError("Utility class");
    }

    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("durability_test")
                        .requires(source -> source.getPermissions()
                                .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .then(CommandManager.argument("pos1", Vec3ArgumentType.vec3())
                                .then(CommandManager.argument("pos2", Vec3ArgumentType.vec3())
                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1))
                                                .then(CommandManager.argument("delayMs", IntegerArgumentType.integer(1))
                                                        .executes(DurabilityTestCommand::runTest))))));

        dispatcher.register(
                CommandManager.literal("durability_test_stop")
                        .requires(source -> source.getPermissions()
                                .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .executes(DurabilityTestCommand::stopTest));
    }

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
        final int delayMs = Math.max(10, IntegerArgumentType.getInteger(context, "delayMs"));

        stopInternal("replaced by a newer durability test");

        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "Chunkis-Durability-Test");
            thread.setDaemon(true);
            return thread;
        });
        final String runId = ChunkTraceStore.nextOperationId("durability");
        executorRef.set(executor);
        runIdRef.set(runId);

        final AtomicInteger remaining = new AtomicInteger(count);
        traceStarted(count, delayMs, worldId(source), runId);

        source.sendFeedback(
                () -> Text.literal("[Chunkis] Starting durability test: " + count + " cycles at " + delayMs + "ms delay"),
                true
        );

        executor.scheduleAtFixedRate(
                () -> {
                    try {
                        final int left = remaining.decrementAndGet();
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
                            source.getServer().execute(() ->
                                    source.sendFeedback(
                                            () -> Text.literal("[Chunkis] Durability test complete."),
                                            true
                                    )
                            );
                            return;
                        }

                        final ServerWorld world = player.getEntityWorld();
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

                        source.getServer().execute(() -> {
                            if (!player.isRemoved()) {
                                player.teleport(
                                        world,
                                        target.x,
                                        target.y,
                                        target.z,
                                        Set.of(),
                                        player.getYaw(),
                                        player.getPitch(),
                                        false
                                );
                            }
                        });
                    } catch (Exception exception) {
                        if (shutdownAndClear(executor, runId)) {
                            traceFailed(exception.getMessage(), worldId(source), runId);
                        }
                        source.getServer().execute(() ->
                                source.sendError(
                                        Text.literal("[Chunkis] Durability test failed: " + exception.getMessage())
                                )
                        );
                    }
                },
                0,
                delayMs,
                TimeUnit.MILLISECONDS
        );

        return 1;
    }

    private static int stopTest(final CommandContext<ServerCommandSource> context) {
        if (stopInternal("stopped manually")) {
            context.getSource().sendFeedback(() -> Text.literal("[Chunkis] Durability test stopped."), true);
        } else {
            context.getSource().sendError(Text.literal("No durability test is currently running."));
        }
        return 1;
    }

    private static boolean stopInternal(final String message) {
        final ScheduledExecutorService executor = executorRef.get();
        final String runId = runIdRef.get();
        if (executor == null || runId == null) {
            return false;
        }
        if (!executorRef.compareAndSet(executor, null)) {
            return false;
        }

        executor.shutdownNow();
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

    private static boolean shutdownAndClear(final ScheduledExecutorService executor, final String runId) {
        executor.shutdownNow();
        if (!executorRef.compareAndSet(executor, null)) {
            return false;
        }
        runIdRef.compareAndSet(runId, null);
        return true;
    }

    static DebugChunkKey toChunkKey(final Vec3d target) {
        return new DebugChunkKey(MathHelper.floor(target.x) >> 4, MathHelper.floor(target.z) >> 4);
    }

    private static String worldId(final ServerCommandSource source) {
        return source.getWorld().getRegistryKey().getValue().toString();
    }

    static void traceStarted(
            final int count,
            final int delayMs,
            final String worldId,
            final String operationId
    ) {
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

    static void traceFailed(
            final String failureMessage,
            final String worldId,
            final String operationId
    ) {
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
