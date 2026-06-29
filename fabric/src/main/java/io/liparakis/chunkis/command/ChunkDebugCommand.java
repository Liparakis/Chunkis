package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceSuspect;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import net.minecraft.command.argument.UuidArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Registers and handles the {@code /chunkis debug} command tree.
 * Delegates actual subcommand execution to {@link ChunkDebugActions}.
 */
public final class ChunkDebugCommand {

    private static final int MAX_LATEST_COUNT = 200;
    private static final int DEFAULT_FAILURE_COUNT = 20;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private ChunkDebugCommand() {
        throw new AssertionError("Utility class");
    }

    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {

        final var watchChunkNode = CommandManager.literal("chunk")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> ChunkDebugActions.watchChunk(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z")
                                ))));

        final var watchRegionNode = CommandManager.literal("region")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> ChunkDebugActions.watchRegion(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z")
                                ))));

        final var watchBlockNode = CommandManager.literal("block")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> ChunkDebugActions.watchBlock(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                IntegerArgumentType.getInteger(ctx, "z")
                                        )))));

        final var watchBlockEntityNode = CommandManager.literal("blockentity")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> ChunkDebugActions.watchBlockEntity(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                IntegerArgumentType.getInteger(ctx, "z")
                                        )))));

        final var watchEntityNode = CommandManager.literal("entity")
                .then(CommandManager.argument("uuid", UuidArgumentType.uuid())
                        .executes(ctx -> ChunkDebugActions.watchEntity(
                                ctx.getSource(),
                                UuidArgumentType.getUuid(ctx, "uuid").toString()
                        )));

        final var watchCommand = CommandManager.literal("watch")
                .then(watchChunkNode)
                .then(watchRegionNode)
                .then(watchBlockNode)
                .then(watchBlockEntityNode)
                .then(watchEntityNode)
                .then(CommandManager.literal("clear")
                        .executes(ctx -> ChunkDebugActions.clearWatchpoints(ctx.getSource())))
                .then(CommandManager.literal("list")
                        .executes(ctx -> ChunkDebugActions.listWatchpoints(ctx.getSource())))
                .then(CommandManager.literal("pending")
                        .executes(ctx -> ChunkDebugActions.pendingWatched(ctx.getSource())))
                .then(CommandManager.literal("latest")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> ChunkDebugActions.latestWatched(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))));

        final var suspectNode = CommandManager.literal("suspect")
                .then(CommandManager.argument("suspectId", LongArgumentType.longArg(1L))
                        .executes(ctx -> ChunkDebugActions.showSuspect(
                                ctx.getSource(),
                                LongArgumentType.getLong(ctx, "suspectId")
                        )))
                .then(CommandManager.literal("timeline")
                        .then(CommandManager.argument("suspectId", LongArgumentType.longArg(1L))
                                .executes(ctx -> ChunkDebugActions.showSuspectTimeline(
                                        ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "suspectId")
                                ))))
                .then(CommandManager.literal("chunk")
                        .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> ChunkDebugActions.showSuspectChunk(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "z")
                                        )))));

        final var exportNode = CommandManager.literal("export")
                .then(CommandManager.literal("latest")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> ChunkDebugActions.exportLatest(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))))
                .then(CommandManager.literal("watched")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> ChunkDebugActions.exportWatched(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))));

        final var debugCommand = CommandManager.literal("debug")
                .then(CommandManager.literal("on")
                        .executes(ctx -> ChunkDebugActions.setLevel(
                                ctx.getSource(), ChunkisDebugLevel.LIFECYCLE,
                                "Chunkis debug set to LIFECYCLE"
                        )))
                .then(CommandManager.literal("off")
                        .executes(ctx -> ChunkDebugActions.setLevel(
                                ctx.getSource(), ChunkisDebugLevel.OFF,
                                "Chunkis debug disabled"
                        )))
                .then(CommandManager.literal("clear")
                        .executes(ctx -> ChunkDebugActions.clear(ctx.getSource())))
                .then(CommandManager.literal("latest")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> ChunkDebugActions.latest(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))))
                .then(exportNode)
                .then(CommandManager.literal("suspects")
                        .executes(ctx -> ChunkDebugActions.listSuspects(ctx.getSource()))
                        .then(CommandManager.literal("clear")
                                .executes(ctx -> ChunkDebugActions.clearSuspects(ctx.getSource()))))
                .then(suspectNode)
                .then(CommandManager.literal("failures")
                        .executes(ctx -> ChunkDebugActions.listFailures(ctx.getSource(), DEFAULT_FAILURE_COUNT))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> ChunkDebugActions.listFailures(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))))
                .then(CommandManager.literal("failure")
                        .then(CommandManager.argument("eventId", LongArgumentType.longArg(1L))
                                .executes(ctx -> ChunkDebugActions.showFailure(
                                        ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "eventId")
                                ))))
                .then(watchCommand);

        dispatcher.register(CommandManager.literal("chunkis")
                .requires(source -> source.getPermissions().hasPermission(
                        new Permission.Level(PermissionLevel.GAMEMASTERS)))
                .then(debugCommand));
    }

    static String formatEvent(final ChunkTraceEvent event) {
        return ChunkDebugOutput.formatEvent(event, TIME_FORMAT);
    }

    static String formatWatchpointSummary() {
        return ChunkDebugOutput.formatWatchpointSummary();
    }

    static String formatNoWatchedTraceMessage() {
        return ChunkDebugOutput.formatNoWatchedTraceMessage(TIME_FORMAT);
    }

    static String formatPendingSnapshot(final PendingChunkSnapshot snapshot) {
        return ChunkDebugOutput.formatPendingSnapshot(snapshot);
    }

    static String formatSuspectSummary(final ChunkTraceSuspect suspect) {
        return ChunkDebugOutput.formatSuspectSummary(suspect);
    }

    static String formatSuspectDetail(final ChunkTraceSuspect suspect) {
        return ChunkDebugOutput.formatSuspectDetail(suspect, TIME_FORMAT);
    }

    static ChatMessage truncateForChat(final String message) {
        return ChunkDebugActions.truncateForChat(message);
    }

    public record PendingChunkSnapshot(
            DebugChunkKey chunkKey,
            boolean trackerDirty,
            AsyncCisSaveManager.PendingSaveSnapshot asyncPending) {}

    public record ChatMessage(String text, boolean truncated) {}
}
