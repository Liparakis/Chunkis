package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceJsonl;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.debug.DebugRegionKey;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

public final class ChunkDebugCommand {

    private static final int MAX_LATEST_COUNT = 200;
    private static final String EXPORT_DIRECTORY = "chunkis/debug";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private ChunkDebugCommand() {
        throw new AssertionError("Utility class");
    }

    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        final var watchCommand = CommandManager.literal("watch")
                .then(CommandManager.literal("chunk")
                        .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> watchChunk(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "x"),
                                                IntegerArgumentType.getInteger(context, "z")
                                        )))))
                .then(CommandManager.literal("region")
                        .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> watchRegion(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "x"),
                                                IntegerArgumentType.getInteger(context, "z")
                                        )))))
                .then(CommandManager.literal("clear")
                        .executes(context -> clearWatchpoints(context.getSource())))
                .then(CommandManager.literal("list")
                        .executes(context -> listWatchpoints(context.getSource())))
                .then(CommandManager.literal("latest")
                        .then(CommandManager.argument(
                                        "count",
                                        IntegerArgumentType.integer(1, MAX_LATEST_COUNT)
                                )
                                .executes(context -> latestWatched(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count")
                                ))));

        final var debugCommand = CommandManager.literal("debug")
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
                                ))))
                .then(CommandManager.literal("export")
                        .then(CommandManager.literal("latest")
                                .then(CommandManager.argument(
                                                "count",
                                                IntegerArgumentType.integer(1, MAX_LATEST_COUNT)
                                        )
                                        .executes(context -> exportLatest(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "count")
                                        ))))
                        .then(CommandManager.literal("watched")
                                .then(CommandManager.argument(
                                                "count",
                                                IntegerArgumentType.integer(1, MAX_LATEST_COUNT)
                                        )
                                        .executes(context -> exportWatched(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "count")
                                        )))))
                .then(watchCommand);

        dispatcher.register(
                CommandManager.literal("chunkis")
                        .requires(source -> source.getPermissions()
                                .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .then(debugCommand)
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
        if (event.operationId() != null) {
            builder.append(" op=").append(event.operationId());
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

    private static int watchChunk(
            final ServerCommandSource source,
            final int chunkX,
            final int chunkZ
    ) {
        ChunkTraceWatchpoints.watchChunk(new DebugChunkKey(chunkX, chunkZ));
        source.sendFeedback(
                () -> Text.literal("[Chunkis] Watching chunk " + chunkX + "," + chunkZ),
                true
        );
        return 1;
    }

    private static int watchRegion(
            final ServerCommandSource source,
            final int regionX,
            final int regionZ
    ) {
        ChunkTraceWatchpoints.watchRegion(new DebugRegionKey(regionX, regionZ));
        source.sendFeedback(
                () -> Text.literal("[Chunkis] Watching region " + regionX + "," + regionZ),
                true
        );
        return 1;
    }

    private static int clearWatchpoints(final ServerCommandSource source) {
        ChunkTraceWatchpoints.clear();
        source.sendFeedback(() -> Text.literal("[Chunkis] Cleared trace watchpoints."), true);
        return 1;
    }

    private static int listWatchpoints(final ServerCommandSource source) {
        final String summary = formatWatchpointSummary();
        source.sendFeedback(() -> Text.literal("[Chunkis] " + summary), false);
        return 1;
    }

    private static int latestWatched(final ServerCommandSource source, final int count) {
        if (ChunkTraceWatchpoints.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No watchpoints configured."));
            return 0;
        }

        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latestMatching(count, ChunkTraceWatchpoints::matches);
        if (newestFirst.isEmpty()) {
            source.sendFeedback(() -> Text.literal("[Chunkis] No watched trace events stored."), false);
            return 1;
        }

        final List<ChunkTraceEvent> oldestFirst = new ArrayList<>(newestFirst);
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            final ChunkTraceEvent event = oldestFirst.get(i);
            source.sendFeedback(() -> Text.literal(formatEvent(event)), false);
        }
        return oldestFirst.size();
    }

    private static int exportLatest(final ServerCommandSource source, final int count) {
        return exportEvents(
                source,
                ChunkTraceStore.snapshotMatching(event -> true),
                "latest-" + count,
                count,
                false
        );
    }

    private static int exportWatched(final ServerCommandSource source, final int count) {
        if (ChunkTraceWatchpoints.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No watchpoints configured."));
            return 0;
        }

        return exportEvents(
                source,
                ChunkTraceStore.snapshotMatching(ChunkTraceWatchpoints::matches),
                "watched-" + count,
                count,
                true
        );
    }

    private static int exportEvents(
            final ServerCommandSource source,
            final List<ChunkTraceEvent> oldestFirst,
            final String scope,
            final int count,
            final boolean watched
    ) {
        if (oldestFirst.isEmpty()) {
            source.sendFeedback(
                    () -> Text.literal(watched
                            ? "[Chunkis] No watched trace events stored."
                            : "[Chunkis] No trace events stored."),
                    false
            );
            return 1;
        }

        final int fromIndex = Math.max(0, oldestFirst.size() - count);
        final List<ChunkTraceEvent> exportEvents = oldestFirst.subList(fromIndex, oldestFirst.size());
        final Path exportPath = resolveExportPath(source, scope);

        try {
            ChunkTraceJsonl.write(exportPath, exportEvents);
        } catch (final IOException e) {
            source.sendError(Text.literal("[Chunkis] Trace export failed: " + e.getMessage()));
            return 0;
        }

        source.sendFeedback(
                () -> Text.literal("[Chunkis] Exported "
                        + exportEvents.size()
                        + " trace events to "
                        + exportPath),
                true
        );
        return exportEvents.size();
    }

    static Path resolveExportPath(final ServerCommandSource source, final String scope) {
        final Path saveRoot = source.getServer().getSavePath(WorldSavePath.ROOT);
        return saveRoot
                .resolve(EXPORT_DIRECTORY)
                .resolve("trace-" + scope + '-' + FILE_TIME_FORMAT.format(Instant.now()) + ".jsonl");
    }

    static String formatWatchpointSummary() {
        final List<DebugChunkKey> chunks = ChunkTraceWatchpoints.watchedChunks();
        final List<DebugRegionKey> regions = ChunkTraceWatchpoints.watchedRegions();
        if (chunks.isEmpty() && regions.isEmpty()) {
            return "No trace watchpoints configured.";
        }

        final StringBuilder builder = new StringBuilder("Watchpoints:");
        if (!chunks.isEmpty()) {
            builder.append(" chunks=").append(formatChunks(chunks));
        }
        if (!regions.isEmpty()) {
            builder.append(" regions=").append(formatRegions(regions));
        }
        return builder.toString();
    }

    private static String formatChunks(final List<DebugChunkKey> chunks) {
        final List<String> parts = new ArrayList<>(chunks.size());
        for (final DebugChunkKey chunk : chunks) {
            parts.add(chunk.x() + "," + chunk.z());
        }
        return String.join(" ", parts);
    }

    private static String formatRegions(final List<DebugRegionKey> regions) {
        final List<String> parts = new ArrayList<>(regions.size());
        for (final DebugRegionKey region : regions) {
            parts.add(region.x() + "," + region.z());
        }
        return String.join(" ", parts);
    }
}
