package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceSuspect;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.minecraft.command.argument.UuidArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

/**
 * Registers and handles the {@code /chunkis debug} command tree.
 * Delegates actual subcommand execution to {@link ChunkDebugActions}.
 */
public final class ChunkDebugCommand {

    /**
     * Maximum events allowed for retrieval in single command lookup.
     */
    private static final int MAX_LATEST_COUNT = 200;

    /**
     * Default count of trace failures returned.
     */
    private static final int DEFAULT_FAILURE_COUNT = 20;

    /**
     * Date format pattern for displaying trace log timings.
     */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkDebugCommand() {
        throw new AssertionError("Utility class");
    }

    /**
     * Registers the {@code /chunkis debug} literal command trees and subnodes.
     *
     * @param dispatcher command dispatcher registry
     */
    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {

        final var watchChunkNode = CommandManager.literal("chunk")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> ChunkDebugActions.watchChunk(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z")))));

        final var watchRegionNode = CommandManager.literal("region")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> ChunkDebugActions.watchRegion(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z")))));

        final var watchBlockNode = CommandManager.literal("block")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> ChunkDebugActions.watchBlock(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                IntegerArgumentType.getInteger(ctx, "z"))))));

        final var watchBlockEntityNode = CommandManager.literal("blockentity")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> ChunkDebugActions.watchBlockEntity(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                IntegerArgumentType.getInteger(ctx, "z"))))));

        final var watchEntityNode = CommandManager.literal("entity")
                .then(CommandManager.argument("uuid", UuidArgumentType.uuid())
                        .executes(ctx -> ChunkDebugActions.watchEntity(ctx.getSource(),
                                UuidArgumentType.getUuid(ctx, "uuid")
                                        .toString())));

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
                                .executes(ctx -> ChunkDebugActions.latestWatched(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))));

        final var suspectNode = CommandManager.literal("suspect")
                .then(CommandManager.argument("suspectId", LongArgumentType.longArg(1L))
                        .executes(ctx -> ChunkDebugActions.showSuspect(ctx.getSource(),
                                LongArgumentType.getLong(ctx, "suspectId"))))
                .then(CommandManager.literal("timeline")
                        .then(CommandManager.argument("suspectId", LongArgumentType.longArg(1L))
                                .executes(ctx -> ChunkDebugActions.showSuspectTimeline(ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "suspectId")))))
                .then(CommandManager.literal("chunk")
                        .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> ChunkDebugActions.showSuspectChunk(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "z"))))));

        final var exportNode = CommandManager.literal("export")
                .then(CommandManager.literal("latest")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> ChunkDebugActions.exportLatest(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(CommandManager.literal("watched")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> ChunkDebugActions.exportWatched(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))));

        final var debugCommand = CommandManager.literal("debug")
                .then(CommandManager.literal("on")
                        .executes(ctx -> ChunkDebugActions.setLevel(ctx.getSource(),
                                ChunkisDebugLevel.LIFECYCLE,
                                "Chunkis debug set to LIFECYCLE")))
                .then(CommandManager.literal("off")
                        .executes(ctx -> ChunkDebugActions.setLevel(ctx.getSource(),
                                ChunkisDebugLevel.OFF,
                                "Chunkis debug disabled")))
                .then(CommandManager.literal("clear")
                        .executes(ctx -> ChunkDebugActions.clear(ctx.getSource())))
                .then(CommandManager.literal("latest")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> ChunkDebugActions.latest(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(exportNode)
                .then(CommandManager.literal("suspects")
                        .executes(ctx -> ChunkDebugActions.listSuspects(ctx.getSource()))
                        .then(CommandManager.literal("clear")
                                .executes(ctx -> ChunkDebugActions.clearSuspects(ctx.getSource()))))
                .then(suspectNode)
                .then(CommandManager.literal("failures")
                        .executes(ctx -> ChunkDebugActions.listFailures(ctx.getSource(), DEFAULT_FAILURE_COUNT))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> ChunkDebugActions.listFailures(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(CommandManager.literal("failure")
                        .then(CommandManager.argument("eventId", LongArgumentType.longArg(1L))
                                .executes(ctx -> ChunkDebugActions.showFailure(ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "eventId")))))
                .then(CommandManager.literal("inspect")
                        .then(CommandManager.literal("neighbors")
                                .executes(ctx -> ChunkDebugActions.inspectNeighborChunks(ctx.getSource())))
                        .then(CommandManager.literal("compare")
                                .executes(ctx -> ChunkDebugActions.inspectCurrentChunkAgainstMca(ctx.getSource()))
                                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> ChunkDebugActions.inspectChunkAgainstMca(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "z"))))))
                        .then(CommandManager.literal("remigrate")
                                .executes(ctx -> ChunkDebugActions.remigrateCurrentChunkFromMca(ctx.getSource()))
                                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> ChunkDebugActions.remigrateChunkFromMca(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "z")))))))
                .then(watchCommand);

        dispatcher.register(CommandManager.literal("chunkis")
                .requires(source -> source.getPermissions()
                        .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                .then(debugCommand));
    }

    /**
     * Formats a trace event into a readable line string.
     *
     * @param event the trace event to format
     * @return formatted string line representation
     */
    static String formatEvent(final ChunkTraceEvent event) {
        return ChunkDebugOutput.formatEvent(event, TIME_FORMAT);
    }

    /**
     * Renders a human-readable list summary of registered watchpoints.
     *
     * @return summary string
     */
    static String formatWatchpointSummary() {
        return ChunkDebugOutput.formatWatchpointSummary();
    }

    /**
     * Generates a notification indicator showing that no trace logs matched active watchpoints.
     *
     * @return notification string
     */
    static String formatNoWatchedTraceMessage() {
        return ChunkDebugOutput.formatNoWatchedTraceMessage(TIME_FORMAT);
    }

    /**
     * Formats a pending status snapshot for watched chunks.
     *
     * @param snapshot the status snapshot record to print
     * @return formatted status line representation
     */
    static String formatPendingSnapshot(final PendingChunkSnapshot snapshot) {
        return ChunkDebugOutput.formatPendingSnapshot(snapshot);
    }

    /**
     * Formats a one-line neighbor inspection row.
     *
     * @param snapshot inspected chunk details
     * @return formatted inspection summary
     */
    static String formatChunkInspectSnapshot(final ChunkInspectSnapshot snapshot) {
        return ChunkDebugOutput.formatChunkInspectSnapshot(snapshot);
    }

    /**
     * Formats a single suspect summary row.
     *
     * @param suspect chunk trace suspect snapshot
     * @return summary row string
     */
    static String formatSuspectSummary(final ChunkTraceSuspect suspect) {
        return ChunkDebugOutput.formatSuspectSummary(suspect);
    }

    /**
     * Formats a suspect snapshot detailed details dump.
     *
     * @param suspect suspect details container
     * @return detailed dump string
     */
    static String formatSuspectDetail(final ChunkTraceSuspect suspect) {
        return ChunkDebugOutput.formatSuspectDetail(suspect, TIME_FORMAT);
    }

    /**
     * Formats and truncates debug messages to fit within Minecraft chat line length guidelines.
     *
     * @param message original string
     * @return ChatMessage record container
     */
    static ChatMessage truncateForChat(final String message) {
        return ChunkDebugActions.truncateForChat(message);
    }

    /**
     * Snapshot record indicating state of pending updates or writes for a watched chunk coordinate.
     *
     * @param chunkKey     chunk coordinate details
     * @param trackerDirty true if chunk tracking has dirty in-memory state changes
     * @param asyncPending details of async file saving thread queue, if any
     */
    public record PendingChunkSnapshot(DebugChunkKey chunkKey, boolean trackerDirty,
                                       AsyncCisSaveManager.PendingSaveSnapshot asyncPending) {

    }

    /**
     * Snapshot record describing live and persisted Chunkis state for one chunk.
     *
     * @param chunkKey         chunk coordinates
     * @param loaded           true if a live WorldChunk is currently loaded
     * @param liveState        summarized delta attached to the loaded chunk, or null
     * @param trackedState     summarized tracker/unload-cache delta, or null
     * @param persistedState   summarized disk delta, or null
     * @param persistedPresent true if a CIS entry exists on disk
     * @param persistenceError storage load error message, or null
     */
    public record ChunkInspectSnapshot(DebugChunkKey chunkKey, boolean loaded, String liveState, String trackedState,
                                       String persistedState, boolean persistedPresent, String persistenceError) {

    }

    /**
     * Chat message envelope indicating truncated status.
     *
     * @param text      message text content
     * @param truncated true if truncation occurred
     */
    public record ChatMessage(String text, boolean truncated) {

    }
}
