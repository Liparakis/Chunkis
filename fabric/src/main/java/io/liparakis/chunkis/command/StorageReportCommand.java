package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.liparakis.chunkis.command.report.StorageMetricsAnalyzer;
import io.liparakis.chunkis.command.report.StorageReportRenderer;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/**
 * Builds the command for reporting physical and logical Chunkis storage usage.
 *
 * <p>Delegates raw analysis to StorageMetricsAnalyzer and rendering to StorageReportRenderer.</p>
 */
public final class StorageReportCommand {

    /**
     * Default count of top-sized regions listed in the summary.
     */
    private static final int DEFAULT_TOP_REGIONS = 8;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private StorageReportCommand() {
        throw new AssertionError("Utility class");
    }

    /**
     * Builds the {@code /chunkis storage report} command.
     *
     * @return the storage command subtree
     */
    static LiteralArgumentBuilder<ServerCommandSource> createNode() {
        return CommandManager.literal("storage")
                .then(reportNode("report"));
    }

    /**
     * Registers the pre-existing command name for compatibility.
     *
     * @param dispatcher command dispatcher registry
     */
    static void registerLegacy(final CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(reportNode("chunkis_storage_report"));
    }

    /**
     * Builds a storage-report literal for the canonical command or its alias.
     *
     * @param literal command literal to use
     * @return storage-report command node
     */
    private static LiteralArgumentBuilder<ServerCommandSource> reportNode(final String literal) {
        return CommandManager.literal(literal)
                .requires(source -> source.getPermissions()
                        .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                .executes(context -> run(context, DEFAULT_TOP_REGIONS))
                .then(CommandManager.argument("topRegions", IntegerArgumentType.integer(1, 32))
                        .executes(context -> run(
                                context,
                                IntegerArgumentType.getInteger(context, "topRegions")
                        )));
    }

    /**
     * Starts the storage report on a background thread and sends the formatted
     * result to the caller from the server thread.
     *
     * <p>The report decodes every stored chunk of the dimension; doing that on the
     * server thread stalled the tick loop long enough to trip the watchdog.</p>
     *
     * @param context    command context information
     * @param topRegions number of top region sizing entries to highlight
     * @return 1 once the report has been started
     */
    private static int run(final CommandContext<ServerCommandSource> context, final int topRegions) {
        final ServerCommandSource source = context.getSource();
        final ServerWorld world = source.getWorld();
        final MinecraftServer server = source.getServer();

        source.sendFeedback(() -> Text.literal("[Chunkis] Building storage report in the background..."), false);
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return StorageMetricsAnalyzer.inspectWorld(world, topRegions);
                    } catch (final IOException e) {
                        throw new CompletionException(e);
                    }
                }, Util.getIoWorkerExecutor())
                .whenComplete((report, error) -> server.execute(() -> {
                    if (error != null) {
                        final Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause()
                                : error;
                        source.sendError(Text.literal("[Chunkis] Storage report failed: " + cause.getMessage()));
                        return;
                    }
                    StorageReportRenderer.sendReport(source, world, report, topRegions);
                }));
        return 1;
    }
}
