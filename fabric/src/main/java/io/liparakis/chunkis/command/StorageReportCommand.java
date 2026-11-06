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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Util;

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
    static LiteralArgumentBuilder<CommandSourceStack> createNode() {
        return Commands.literal("storage")
                .then(reportNode("report"));
    }

    /**
     * Registers the pre-existing command name for compatibility.
     *
     * @param dispatcher command dispatcher registry
     */
    static void registerLegacy(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(reportNode("chunkis_storage_report"));
    }

    /**
     * Builds a storage-report literal for the canonical command or its alias.
     *
     * @param literal command literal to use
     * @return storage-report command node
     */
    private static LiteralArgumentBuilder<CommandSourceStack> reportNode(final String literal) {
        return Commands.literal(literal)
                .requires(source -> source.permissions()
                        .hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
                .executes(context -> run(context, DEFAULT_TOP_REGIONS))
                .then(Commands.argument("topRegions", IntegerArgumentType.integer(1, 32))
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
    private static int run(final CommandContext<CommandSourceStack> context, final int topRegions) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel world = source.getLevel();
        final MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("[Chunkis] Building storage report in the background..."), false);
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return StorageMetricsAnalyzer.inspectWorld(world, topRegions);
                    } catch (final IOException e) {
                        throw new CompletionException(e);
                    }
                }, Util.ioPool())
                .whenComplete((report, error) -> server.execute(() -> {
                    if (error != null) {
                        final Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause()
                                : error;
                        source.sendFailure(Component.literal("[Chunkis] Storage report failed: " + cause.getMessage()));
                        return;
                    }
                    StorageReportRenderer.sendReport(source, world, report, topRegions);
                }));
        return 1;
    }
}
