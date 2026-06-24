package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.ChunkisDebugLevel;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class ChunkDebugCommand {

    private static final int MAX_LATEST_COUNT = 200;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private ChunkDebugCommand() {
        throw new AssertionError("Utility class");
    }

    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("chunkis")
                        .requires(source -> source.getPermissions()
                                .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .then(CommandManager.literal("debug")
                                .then(CommandManager.literal("on")
                                        .executes(context -> setLevel(
                                                context.getSource(),
                                                ChunkisDebugLevel.LIFECYCLE,
                                                "Chunkis debug set to LIFECYCLE"
                                        )))
                                .then(CommandManager.literal("off")
                                        .executes(context -> setLevel(
                                                context.getSource(),
                                                ChunkisDebugLevel.OFF,
                                                "Chunkis debug disabled"
                                        )))
                                .then(CommandManager.literal("clear")
                                        .executes(context -> clear(context.getSource())))
                                .then(CommandManager.literal("latest")
                                        .then(CommandManager.argument(
                                                        "count",
                                                        IntegerArgumentType.integer(1, MAX_LATEST_COUNT)
                                                )
                                                .executes(context -> latest(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "count")
                                                )))))
        );
    }

    static String formatEvent(final ChunkTraceEvent event) {
        final StringBuilder builder = new StringBuilder(192);
        builder.append('#').append(event.eventId())
                .append(' ')
                .append(TIME_FORMAT.format(Instant.ofEpochMilli(event.timestampMillis())))
                .append(" UTC ")
                .append(event.eventType())
                .append(' ')
                .append(event.severity())
                .append('/')
                .append(event.reason())
                .append(" [")
                .append(event.domain())
                .append(']');

        if (event.worldId() != null) {
            builder.append(" world=").append(event.worldId());
        }
        if (event.chunkKey() != null) {
            builder.append(" chunk=").append(event.chunkKey().x()).append(',').append(event.chunkKey().z());
        }
        if (event.regionKey() != null) {
            builder.append(" region=").append(event.regionKey().x()).append(',').append(event.regionKey().z());
        }
        if (event.dirtyState() != null) {
            builder.append(" dirty=").append(event.dirtyState());
        }
        if (event.byteSize() != null) {
            builder.append(" bytes=").append(event.byteSize());
        }

        builder.append(" src=").append(event.source());
        builder.append(" thread=").append(event.threadName());
        builder.append(" msg=").append(event.message());
        return builder.toString();
    }

    private static int setLevel(
            final ServerCommandSource source,
            final ChunkisDebugLevel level,
            final String message
    ) {
        ChunkisDebugConfig.setLevel(level);
        source.sendFeedback(() -> Text.literal("[Chunkis] " + message), true);
        return 1;
    }

    private static int clear(final ServerCommandSource source) {
        ChunkTraceStore.clear();
        source.sendFeedback(() -> Text.literal("[Chunkis] Cleared in-memory trace events."), true);
        return 1;
    }

    private static int latest(final ServerCommandSource source, final int count) {
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latest(count);
        if (newestFirst.isEmpty()) {
            source.sendFeedback(() -> Text.literal("[Chunkis] No trace events stored."), false);
            return 1;
        }

        final List<ChunkTraceEvent> oldestFirst = new ArrayList<>(newestFirst);
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            final ChunkTraceEvent event = oldestFirst.get(i);
            source.sendFeedback(() -> Text.literal(formatEvent(event)), false);
        }
        return oldestFirst.size();
    }
}
