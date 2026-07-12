package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

/**
 * Registers the single top-level {@code /chunkis} command tree.
 */
public final class ChunkisCommand {

    /**
     * Prevent instantiation.
     */
    private ChunkisCommand() {
        throw new AssertionError("Utility class");
    }

    /**
     * Registers Chunkis commands and compatibility aliases.
     *
     * @param dispatcher command dispatcher registry
     */
    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("chunkis")
                .requires(source -> source.getPermissions()
                        .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                .then(ChunkDebugCommand.createNode())
                .then(StorageReportCommand.createNode())
                .then(DurabilityTestCommand.createNode()));
        StorageReportCommand.registerLegacy(dispatcher);
        DurabilityTestCommand.registerLegacy(dispatcher);
    }
}
