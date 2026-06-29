package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.liparakis.chunkis.command.report.StorageMetricsAnalyzer;
import io.liparakis.chunkis.command.report.StorageReportModels.StorageReport;
import io.liparakis.chunkis.command.report.StorageReportRenderer;
import java.io.IOException;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/**
 * Reports physical and logical Chunkis storage usage for one world/dimension.
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
     * Registers the {@code /chunkis_storage_report} command.
     *
     * @param dispatcher command dispatcher registry
     */
    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("chunkis_storage_report")
                        .requires(source -> source.getPermissions()
                                .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .executes(context -> run(context, DEFAULT_TOP_REGIONS))
                        .then(CommandManager.argument("topRegions", IntegerArgumentType.integer(1, 32))
                                .executes(context -> run(
                                        context,
                                        IntegerArgumentType.getInteger(context, "topRegions")
                                )))
        );
    }

    /**
     * Executes the storage report and sends the formatted result to the caller.
     *
     * @param context    command context information
     * @param topRegions number of top region sizing entries to highlight
     * @return 1 on success, 0 on IO failure
     */
    private static int run(final CommandContext<ServerCommandSource> context, final int topRegions) {
        final ServerCommandSource source = context.getSource();
        final ServerWorld world = source.getWorld();

        try {
            final StorageReport report = StorageMetricsAnalyzer.inspectWorld(world, topRegions);
            StorageReportRenderer.sendReport(source, world, report, topRegions);
            return 1;
        } catch (final IOException e) {
            source.sendError(Text.literal("[Chunkis] Storage report failed: " + e.getMessage()));
            return 0;
        }
    }
}
