package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

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
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chunkis")
                .requires(source -> source.permissions()
                        .hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
                .then(ChunkDebugCommand.createNode())
                .then(StorageReportCommand.createNode())
                .then(DurabilityTestCommand.createNode()));
        StorageReportCommand.registerLegacy(dispatcher);
        DurabilityTestCommand.registerLegacy(dispatcher);
    }
}
