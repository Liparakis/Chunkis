package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.trace.ChunkTraceJsonl;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.model.ChunkTraceSuspect;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.storage.AsyncCisSaveManager;
import io.liparakis.chunkis.storage.BaseChunkCaptureScheduler;
import io.liparakis.chunkis.world.GlobalChunkTracker;
import net.minecraft.command.argument.UuidArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Registers and handles the {@code /chunkis debug} command tree, providing in-game access
 * to chunk trace diagnostics: viewing recent events, managing watchpoints, inspecting suspects,
 * listing failures, and exporting trace data to JSONL files.
 *
 * <p>All subcommands require the {@link PermissionLevel#GAMEMASTERS} permission level.
 *
 * <p>This is a non-instantiable utility class. Use {@link #register(CommandDispatcher)} to
 * attach the command tree to a Brigadier dispatcher.
 */
public final class ChunkDebugCommand {

    /**
     * Maximum number of events returned by any {@code latest} or {@code watched} subcommand.
     */
    private static final int MAX_LATEST_COUNT = 200;

    /**
     * Default number of failure events shown by {@code /chunkis debug failures} with no count argument.
     */
    private static final int DEFAULT_FAILURE_COUNT = 20;

    /**
     * Directory relative to the world save root where JSONL trace exports and chat dumps are written.
     */
    private static final String EXPORT_DIRECTORY = "chunkis/debug";

    /**
     * Maximum UTF-8 byte size of a chat message sent to the command source.
     * Messages exceeding this are truncated and the full content is written to disk instead.
     */
    private static final int MAX_CHAT_MESSAGE_BYTES = 12_000;

    /**
     * Suffix appended to chat messages that were truncated due to {@link #MAX_CHAT_MESSAGE_BYTES}.
     */
    private static final String CHAT_TRUNCATION_SUFFIX = "[truncated; full trace written to log/file]";

    /**
     * Precomputed suffix with a leading space and its UTF-8 byte length.
     * Both are constants computed once here rather than on every truncation call.
     */
    private static final String CHAT_TRUNCATION_SUFFIX_PADDED = " " + CHAT_TRUNCATION_SUFFIX;
    private static final int CHAT_TRUNCATION_SUFFIX_BYTES =
            CHAT_TRUNCATION_SUFFIX_PADDED.getBytes(StandardCharsets.UTF_8).length;

    /**
     * Formats event timestamps as {@code HH:mm:ss.SSS} in UTC for in-game display.
     */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /**
     * Formats timestamps for use in export file names as {@code yyyyMMdd-HHmmss-SSS} in UTC.
     */
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private ChunkDebugCommand() {
        throw new AssertionError("Utility class");
    }

    /**
     * Registers the {@code /chunkis debug} command tree with the given dispatcher.
     *
     * <p>The tree requires {@link PermissionLevel#GAMEMASTERS} and exposes subcommands for:
     * enabling/disabling debug logging, clearing/listing/exporting trace events, inspecting
     * suspects and failures, and managing payload watchpoints.
     *
     * @param dispatcher the Brigadier command dispatcher to register into
     */
    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {

        // -- watch subcommands
        final var watchChunkNode = CommandManager.literal("chunk")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> watchChunk(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z")
                                ))));

        final var watchRegionNode = CommandManager.literal("region")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> watchRegion(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z")
                                ))));

        final var watchBlockNode = CommandManager.literal("block")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> watchBlock(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                IntegerArgumentType.getInteger(ctx, "z")
                                        )))));

        final var watchBlockEntityNode = CommandManager.literal("blockentity")
                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> watchBlockEntity(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                IntegerArgumentType.getInteger(ctx, "z")
                                        )))));

        final var watchEntityNode = CommandManager.literal("entity")
                .then(CommandManager.argument("uuid", UuidArgumentType.uuid())
                        .executes(ctx -> watchEntity(
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
                        .executes(ctx -> clearWatchpoints(ctx.getSource())))
                .then(CommandManager.literal("list")
                        .executes(ctx -> listWatchpoints(ctx.getSource())))
                .then(CommandManager.literal("pending")
                        .executes(ctx -> pendingWatched(ctx.getSource())))
                .then(CommandManager.literal("latest")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> latestWatched(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))));

        //suspect subcommands
        final var suspectNode = CommandManager.literal("suspect")
                .then(CommandManager.argument("suspectId", LongArgumentType.longArg(1L))
                        .executes(ctx -> showSuspect(
                                ctx.getSource(),
                                LongArgumentType.getLong(ctx, "suspectId")
                        )))
                .then(CommandManager.literal("timeline")
                        .then(CommandManager.argument("suspectId", LongArgumentType.longArg(1L))
                                .executes(ctx -> showSuspectTimeline(
                                        ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "suspectId")
                                ))))
                .then(CommandManager.literal("chunk")
                        .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> showSuspectChunk(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "z")
                                        )))));

        //export subcommands
        final var exportNode = CommandManager.literal("export")
                .then(CommandManager.literal("latest")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> exportLatest(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))))
                .then(CommandManager.literal("watched")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> exportWatched(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))));

        //debug command root
        final var debugCommand = CommandManager.literal("debug")
                .then(CommandManager.literal("on")
                        .executes(ctx -> setLevel(
                                ctx.getSource(), ChunkisDebugLevel.LIFECYCLE,
                                "Chunkis debug set to LIFECYCLE"
                        )))
                .then(CommandManager.literal("off")
                        .executes(ctx -> setLevel(
                                ctx.getSource(), ChunkisDebugLevel.OFF,
                                "Chunkis debug disabled"
                        )))
                .then(CommandManager.literal("clear")
                        .executes(ctx -> clear(ctx.getSource())))
                .then(CommandManager.literal("latest")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> latest(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))))
                .then(exportNode)
                .then(CommandManager.literal("suspects")
                        .executes(ctx -> listSuspects(ctx.getSource()))
                        .then(CommandManager.literal("clear")
                                .executes(ctx -> clearSuspects(ctx.getSource()))))
                .then(suspectNode)
                .then(CommandManager.literal("failures")
                        .executes(ctx -> listFailures(ctx.getSource(), DEFAULT_FAILURE_COUNT))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT))
                                .executes(ctx -> listFailures(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))))
                .then(CommandManager.literal("failure")
                        .then(CommandManager.argument("eventId", LongArgumentType.longArg(1L))
                                .executes(ctx -> showFailure(
                                        ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "eventId")
                                ))))
                .then(watchCommand);

        dispatcher.register(CommandManager.literal("chunkis")
                .requires(source -> source.getPermissions().hasPermission(
                        new Permission.Level(PermissionLevel.GAMEMASTERS)))
                .then(debugCommand));
    }

    /**
     * Formats a single {@link ChunkTraceEvent} as a human-readable log line suitable for
     * in-game chat or a log file. Optional fields are appended only when non-null.
     *
     * <p>Output format:
     * {@code #<id> HH:mm:ss.SSS UTC <type> <severity>/<reason> [<domain>]
     * [world=...] [chunk=x,z] [region=x,z] [dirty=...] [bytes=...] [op=...]
     * [payload=...] [stage=...] [payloadSummary=...] src=... thread=... msg=...}
     *
     * @param event the event to format; must not be null
     * @return a single-line string representation of the event
     */
    static String formatEvent(final ChunkTraceEvent event) {
        final StringBuilder sb = new StringBuilder(192);
        sb.append('#').append(event.eventId())
                .append(' ').append(TIME_FORMAT.format(Instant.ofEpochMilli(event.timestampMillis())))
                .append(" UTC ").append(event.eventType())
                .append(' ').append(event.severity()).append('/').append(event.reason())
                .append(" [").append(event.domain()).append(']');

        if (event.worldId() != null) sb.append(" world=").append(event.worldId());
        if (event.chunkKey() != null)
            sb.append(" chunk=").append(event.chunkKey().x()).append(',').append(event.chunkKey().z());
        if (event.regionKey() != null)
            sb.append(" region=").append(event.regionKey().x()).append(',').append(event.regionKey().z());
        if (event.dirtyState() != null) sb.append(" dirty=").append(event.dirtyState());
        if (event.byteSize() != null) sb.append(" bytes=").append(event.byteSize());
        if (event.operationId() != null) sb.append(" op=").append(event.operationId());
        if (event.payloadWatchTarget() != null) sb.append(" payload=").append(event.payloadWatchTarget().describe());
        if (event.payloadWatchStage() != null) sb.append(" stage=").append(event.payloadWatchStage());
        if (event.payloadWatchSummary() != null) sb.append(" payloadSummary=").append(event.payloadWatchSummary());

        sb.append(" src=").append(event.source())
                .append(" thread=").append(event.threadName())
                .append(" msg=").append(event.message());
        return sb.toString();
    }

    /**
     * Sets the global Chunkis debug level and notifies the command source.
     */
    private static int setLevel(
            final ServerCommandSource source, final ChunkisDebugLevel level,
            final String message) {
        ChunkisDebugConfig.setLevel(level);
        sendFeedback(source, "[Chunkis] " + message, true);
        return 1;
    }

    /**
     * Clears all in-memory trace events from {@link ChunkTraceStore}.
     */
    private static int clear(final ServerCommandSource source) {
        ChunkTraceStore.clear();
        sendFeedback(source, "[Chunkis] Cleared in-memory trace events.", true);
        return 1;
    }

    /**
     * Displays the {@code count} most recent trace events in chronological order.
     * Events are retrieved newest-first from the store and printed oldest-first.
     */
    private static int latest(final ServerCommandSource source, final int count) {
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latest(count);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, "[Chunkis] No trace events stored.", false);
            return 1;
        }
        return sendEventsOldestFirst(source, newestFirst);
    }

    /**
     * Lists all current suspect chunks recorded by the trace system.
     */
    private static int listSuspects(final ServerCommandSource source) {
        final List<ChunkTraceSuspect> suspects = ChunkTraceStore.suspects();
        if (suspects.isEmpty()) {
            sendFeedback(source, "[Chunkis] No suspicious chunks recorded.", false);
            return 1;
        }
        for (final ChunkTraceSuspect suspect : suspects) {
            sendFeedback(source, formatSuspectSummary(suspect), false);
        }
        return suspects.size();
    }

    /**
     * Clears all retained suspect snapshots from {@link ChunkTraceStore}.
     */
    private static int clearSuspects(final ServerCommandSource source) {
        ChunkTraceStore.clearSuspects();
        sendFeedback(source, "[Chunkis] Cleared retained suspect snapshots.", true);
        return 1;
    }

    /**
     * Shows detailed information for the suspect identified by {@code suspectId}.
     */
    private static int showSuspect(final ServerCommandSource source, final long suspectId) {
        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(suspectId);
        if (suspect == null) {
            source.sendError(Text.literal("[Chunkis] No suspect with id " + suspectId));
            return 0;
        }
        sendFeedback(source, formatSuspectDetail(suspect), false);
        return 1;
    }

    /**
     * Shows detailed information for the most recent suspect recorded for chunk
     * ({@code chunkX}, {@code chunkZ}).
     */
    private static int showSuspectChunk(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(new DebugChunkKey(chunkX, chunkZ));
        if (suspect == null) {
            source.sendError(Text.literal("[Chunkis] No suspicious trace for chunk " + chunkX + "," + chunkZ));
            return 0;
        }
        sendFeedback(source, formatSuspectDetail(suspect), false);
        return 1;
    }

    /**
     * Shows the retained event timeline for suspect {@code suspectId}, printed in chronological order.
     * The timeline is stored separately from the general trace ring-buffer and may be empty if
     * the suspect was recorded before timeline retention was enabled.
     */
    private static int showSuspectTimeline(final ServerCommandSource source, final long suspectId) {
        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(suspectId);
        if (suspect == null) {
            source.sendError(Text.literal("[Chunkis] No suspect with id " + suspectId));
            return 0;
        }
        final List<ChunkTraceEvent> timeline = ChunkTraceStore.suspectTimeline(suspectId);
        if (timeline.isEmpty()) {
            sendFeedback(source, "[Chunkis] No retained suspect timeline for " + suspectId, false);
            return 1;
        }
        sendFeedback(
                source, "[Chunkis] Retained suspect timeline for " + suspectId
                        + " (" + timeline.size() + " events)", false
        );
        for (final ChunkTraceEvent event : timeline) {
            sendFeedback(source, formatEvent(event), false);
        }
        return timeline.size();
    }

    /**
     * Lists up to {@code count} failure events in chronological order.
     * Events are retrieved newest-first and printed oldest-first.
     */
    private static int listFailures(final ServerCommandSource source, final int count) {
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latestFailures(count);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, "[Chunkis] No suspicious failure events stored.", false);
            return 1;
        }
        return sendEventsOldestFirst(source, newestFirst);
    }

    /**
     * Shows the trace event with the given {@code eventId}.
     */
    private static int showFailure(final ServerCommandSource source, final long eventId) {
        final ChunkTraceEvent event = ChunkTraceStore.findEvent(eventId);
        if (event == null) {
            source.sendError(Text.literal("[Chunkis] No trace event with id " + eventId));
            return 0;
        }
        sendFeedback(source, formatEvent(event), false);
        return 1;
    }

    /**
     * Adds a chunk watchpoint for ({@code chunkX}, {@code chunkZ}).
     */
    private static int watchChunk(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        ChunkTraceWatchpoints.watchChunk(new DebugChunkKey(chunkX, chunkZ));
        sendFeedback(source, "[Chunkis] Watching chunk " + chunkX + "," + chunkZ, true);
        return 1;
    }

    /**
     * Adds a region watchpoint for ({@code regionX}, {@code regionZ}).
     */
    private static int watchRegion(final ServerCommandSource source, final int regionX, final int regionZ) {
        ChunkTraceWatchpoints.watchRegion(new DebugRegionKey(regionX, regionZ));
        sendFeedback(source, "[Chunkis] Watching region " + regionX + "," + regionZ, true);
        return 1;
    }

    /**
     * Adds a block payload watchpoint at ({@code x}, {@code y}, {@code z}) in the source's current world.
     */
    private static int watchBlock(final ServerCommandSource source, final int x, final int y, final int z) {
        final String worldId = worldIdOf(source);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block(worldId, x, y, z));
        sendFeedback(source, "[Chunkis] Watching block " + x + "," + y + "," + z + " in " + worldId, true);
        return 1;
    }

    /**
     * Adds a block-entity payload watchpoint at ({@code x}, {@code y}, {@code z}) in the source's
     * current world.
     */
    private static int watchBlockEntity(final ServerCommandSource source, final int x, final int y, final int z) {
        final String worldId = worldIdOf(source);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.blockEntity(worldId, x, y, z));
        sendFeedback(source, "[Chunkis] Watching block entity " + x + "," + y + "," + z + " in " + worldId, true);
        return 1;
    }

    /**
     * Adds an entity payload watchpoint for {@code uuid} in the source's current world.
     *
     * @param uuid the entity UUID as a string, as parsed by {@link UuidArgumentType}
     */
    private static int watchEntity(final ServerCommandSource source, final String uuid) {
        final String worldId = worldIdOf(source);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.entity(worldId, uuid));
        sendFeedback(source, "[Chunkis] Watching entity " + uuid + " in " + worldId, true);
        return 1;
    }

    /**
     * Removes all configured trace watchpoints.
     */
    private static int clearWatchpoints(final ServerCommandSource source) {
        ChunkTraceWatchpoints.clear();
        sendFeedback(source, "[Chunkis] Cleared trace watchpoints.", true);
        return 1;
    }

    /**
     * Sends the current watchpoint summary to the command source.
     */
    private static int listWatchpoints(final ServerCommandSource source) {
        sendFeedback(source, "[Chunkis] " + formatWatchpointSummary(), false);
        return 1;
    }

    /**
     * Shows up to {@code count} events matching the current watchpoints in chronological order.
     * Returns an error if no watchpoints are configured.
     */
    private static int latestWatched(final ServerCommandSource source, final int count) {
        if (ChunkTraceWatchpoints.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No watchpoints configured."));
            return 0;
        }
        final List<ChunkTraceEvent> newestFirst =
                ChunkTraceStore.latestMatching(count, ChunkTraceWatchpoints::matches);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, formatNoWatchedTraceMessage(), false);
            return 1;
        }
        return sendEventsOldestFirst(source, newestFirst);
    }

    /**
     * Shows the pending save/capture state for each watched chunk, drawn from live snapshots
     * of the global chunk tracker, async save manager, and base capture scheduler.
     */
    private static int pendingWatched(final ServerCommandSource source) {
        final List<DebugChunkKey> watchedChunks = ChunkTraceWatchpoints.watchedChunks();
        if (watchedChunks.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No chunk watchpoints configured."));
            return 0;
        }
        final var world = source.getWorld();
        final var trackerPending = GlobalChunkTracker.getPendingDeltas(world);
        final var asyncPending = AsyncCisSaveManager.snapshot(world);
        final var basePending = BaseChunkCaptureScheduler.snapshot(world);

        for (final DebugChunkKey chunkKey : watchedChunks) {
            final var snapshot = new PendingChunkSnapshot(
                    chunkKey,
                    trackerPending.containsKey(new ChunkPos(chunkKey.x(), chunkKey.z())),
                    asyncPending.get(chunkKey),
                    basePending.get(chunkKey)
            );
            sendFeedback(source, formatPendingSnapshot(snapshot), false);
        }
        return watchedChunks.size();
    }

    /**
     * Exports the {@code count} most recent trace events (no filter) to a JSONL file.
     * The export is taken from a consistent point-in-time snapshot of the store.
     */
    private static int exportLatest(final ServerCommandSource source, final int count) {
        return exportEvents(
                source, ChunkTraceStore.snapshotMatching(event -> true),
                "latest-" + count, count, false
        );
    }

    /**
     * Exports up to {@code count} events matching the current watchpoints to a JSONL file.
     * Returns an error if no watchpoints are configured.
     */
    private static int exportWatched(final ServerCommandSource source, final int count) {
        if (ChunkTraceWatchpoints.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No watchpoints configured."));
            return 0;
        }
        return exportEvents(
                source, ChunkTraceStore.snapshotMatching(ChunkTraceWatchpoints::matches),
                "watched-" + count, count, true
        );
    }

    /**
     * Writes the last {@code count} events from {@code oldestFirst} to a JSONL export file.
     *
     * @param oldestFirst oldest-to-newest ordered snapshot from {@link ChunkTraceStore#snapshotMatching}
     * @param scope       label embedded in the export file name (e.g., {@code "latest-20"})
     * @param count       how many of the most-recent events in {@code oldestFirst} to export
     * @param watched     if {@code true}, uses watched-specific wording in the empty-list message
     * @return the number of events exported, or 1 if the list was empty
     */
    private static int exportEvents(
            final ServerCommandSource source, final List<ChunkTraceEvent> oldestFirst,
            final String scope, final int count, final boolean watched) {
        if (oldestFirst.isEmpty()) {
            sendFeedback(
                    source,
                    watched ? formatNoWatchedTraceMessage() : "[Chunkis] No trace events stored.",
                    false
            );
            return 1;
        }
        final int fromIndex = Math.max(0, oldestFirst.size() - count);
        final List<ChunkTraceEvent> exportSlice = oldestFirst.subList(fromIndex, oldestFirst.size());
        final Path exportPath = resolveExportPath(source, scope);
        try {
            ChunkTraceJsonl.write(exportPath, exportSlice);
        } catch (final IOException e) {
            source.sendError(Text.literal("[Chunkis] Trace export failed: " + e.getMessage()));
            return 0;
        }
        sendFeedback(source, "[Chunkis] Exported " + exportSlice.size() + " trace events to " + exportPath, true);
        return exportSlice.size();
    }

    /**
     * Returns a summary of all currently configured watchpoints, or a "none configured" message.
     * Used both for {@code /chunkis debug watch list} and as context in empty-result messages.
     */
    static String formatWatchpointSummary() {
        final List<DebugChunkKey> chunks = ChunkTraceWatchpoints.watchedChunks();
        final List<DebugRegionKey> regions = ChunkTraceWatchpoints.watchedRegions();
        final List<PayloadWatchTarget> payloads = ChunkTraceWatchpoints.watchedPayloads();
        if (chunks.isEmpty() && regions.isEmpty() && payloads.isEmpty()) {
            return "No trace watchpoints configured.";
        }
        final StringBuilder sb = new StringBuilder("Watchpoints:");
        if (!chunks.isEmpty()) sb.append(" chunks=").append(formatChunks(chunks));
        if (!regions.isEmpty()) sb.append(" regions=").append(formatRegions(regions));
        if (!payloads.isEmpty()) sb.append(" payloads=").append(formatPayloads(payloads));
        return sb.toString();
    }

    /**
     * Returns a diagnostic message explaining why a watched-event query returned no results.
     * Distinguishes "no payload events exist at all" from "events exist but don't match watchpoints"
     * by peeking at the most recent payload-watch event in the store.
     *
     * <p>Always returns a non-null, non-empty string. The {@code @SuppressWarnings} suppresses a
     * false-positive IDE warning this method returns two distinct strings depending on state.
     */
    @SuppressWarnings("SameReturnValue")
    static String formatNoWatchedTraceMessage() {
        final List<ChunkTraceEvent> payloadEvents = ChunkTraceStore.latestMatching(
                1,
                event -> event.payloadWatchTarget() != null
        );
        if (payloadEvents.isEmpty()) {
            return "[Chunkis] No watched trace events stored. Store currently has no payload-watch events. "
                    + formatWatchpointSummary();
        }
        return "[Chunkis] No watched trace events matched current watchpoints. latestPayloadEvent="
                + formatEvent(payloadEvents.getFirst()) + " " + formatWatchpointSummary();
    }

    /**
     * Formats the pending save/capture state of a single watched chunk as a flat key=value string.
     * Async and base-capture details are appended only when those queues have an entry for this chunk.
     *
     * @param snapshot the snapshot to format; must not be null
     * @return a flat diagnostic string suitable for in-game chat
     */
    static String formatPendingSnapshot(final PendingChunkSnapshot snapshot) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("chunk=").append(snapshot.chunkKey().x()).append(',').append(snapshot.chunkKey().z())
                .append(" trackerDirty=").append(snapshot.trackerDirty())
                .append(" asyncQueued=").append(snapshot.asyncPending() != null)
                .append(" baseCaptureQueued=").append(snapshot.baseCapturePending() != null);

        if (snapshot.asyncPending() != null) {
            sb.append(" asyncOp=").append(snapshot.asyncPending().operationId())
                    .append(" asyncGeneration=").append(snapshot.asyncPending().generation())
                    .append(" asyncDirty=").append(snapshot.asyncPending().dirtyState());
        }
        if (snapshot.baseCapturePending() != null) {
            sb.append(" baseCaptureDirty=").append(snapshot.baseCapturePending().dirtyState());
        }
        return sb.toString();
    }

    /**
     * Formats a one-line summary of a suspect for use in the {@code /chunkis debug suspects} list.
     * Includes id, chunk, optional region, reason, latest event type, severity, and occurrence count.
     *
     * @param suspect the suspect to summarize; must not be null
     * @return a flat diagnostic string suitable for in-game chat
     */
    static String formatSuspectSummary(final ChunkTraceSuspect suspect) {
        final StringBuilder sb = new StringBuilder(160);
        sb.append("id=").append(suspect.suspectId())
                .append(" chunk=").append(suspect.chunkKey().x()).append(',').append(suspect.chunkKey().z());
        if (suspect.regionKey() != null) {
            sb.append(" region=").append(suspect.regionKey().x()).append(',').append(suspect.regionKey().z());
        }
        sb.append(" reason=").append(suspect.reason())
                .append(" latest=").append(suspect.latestEvent().eventType())
                .append('#').append(suspect.latestEvent().eventId())
                .append(" severity=").append(suspect.severity())
                .append(" occurrences=").append(suspect.occurrenceCount());
        return sb.toString();
    }

    /**
     * Formats a detailed multi-field description of a suspect for use in
     * {@code /chunkis debug suspect <id>} and {@code /chunkis debug suspect chunk <x> <z>}.
     * Includes timestamps, original and latest events, timeline size, and a timeline inspect hint.
     *
     * @param suspect the suspect to detail; must not be null
     * @return a flat diagnostic string suitable for in-game chat
     */
    static String formatSuspectDetail(final ChunkTraceSuspect suspect) {
        final StringBuilder sb = new StringBuilder(384);
        sb.append("suspectId=").append(suspect.suspectId())
                .append(" chunk=").append(suspect.chunkKey().x()).append(',').append(suspect.chunkKey().z());
        if (suspect.regionKey() != null) {
            sb.append(" region=").append(suspect.regionKey().x()).append(',').append(suspect.regionKey().z());
        }
        sb.append(" reason=").append(suspect.reason())
                .append(" original=").append(suspect.originalFailureEvent().eventType())
                .append('#').append(suspect.originalFailureEvent().eventId())
                .append(" latest=").append(suspect.latestEvent().eventType())
                .append('#').append(suspect.latestEvent().eventId())
                .append(" severity=").append(suspect.severity())
                .append(" firstSeen=").append(TIME_FORMAT.format(
                        Instant.ofEpochMilli(suspect.firstSeenTimestampMillis()))).append(" UTC")
                .append(" lastSeen=").append(TIME_FORMAT.format(
                        Instant.ofEpochMilli(suspect.lastSeenTimestampMillis()))).append(" UTC")
                .append(" occurrences=").append(suspect.occurrenceCount());
        if (suspect.operationId() != null) {
            sb.append(" op=").append(suspect.operationId());
        }
        sb.append(" timeline=").append(suspect.copiedTimeline().size())
                .append(" latestMsg=").append(suspect.latestMessage())
                .append(" inspect=/chunkis debug suspect timeline ").append(suspect.suspectId());
        return sb.toString();
    }

    /**
     * Formats chunk keys as {@code "x1,z1 x2,z2 ..."}, space-separated.
     *
     * @param chunks non-null, non-empty list of chunk keys
     * @return space-separated {@code x,z} coordinate pairs
     */
    private static String formatChunks(final List<DebugChunkKey> chunks) {
        return chunks.stream()
                .map(c -> c.x() + "," + c.z())
                .collect(Collectors.joining(" "));
    }

    /**
     * Formats region keys as {@code "x1,z1 x2,z2 ..."}, space-separated.
     *
     * @param regions non-null, non-empty list of region keys
     * @return space-separated {@code x,z} coordinate pairs
     */
    private static String formatRegions(final List<DebugRegionKey> regions) {
        return regions.stream()
                .map(r -> r.x() + "," + r.z())
                .collect(Collectors.joining(" "));
    }

    /**
     * Formats payload watch targets as their descriptions joined by {@code " | "}.
     *
     * @param payloads non-null, non-empty list of watch targets
     * @return pipe-separated descriptions
     */
    private static String formatPayloads(final List<PayloadWatchTarget> payloads) {
        return payloads.stream()
                .map(PayloadWatchTarget::describe)
                .collect(Collectors.joining(" | "));
    }

    /**
     * Truncates {@code message} so its UTF-8 byte length fits within {@link #MAX_CHAT_MESSAGE_BYTES}.
     * If truncated, appends {@link #CHAT_TRUNCATION_SUFFIX_PADDED}.
     *
     * <p><b>Performance:</b> the string is encoded to bytes once, then the byte boundary is found
     * by walking back from {@code targetBytes} to skip any UTF-8 continuation bytes
     * ({@code 10xxxxxx}, i.e. {@code (b & 0xC0) == 0x80}). This is O(n) with two allocations total,
     * compared to the naive O(nÂ²) approach of re-encoding a shrinking substring on every loop step.
     *
     * @param message the message to evaluate; {@code null} is treated as an empty string
     * @return a {@link ChatMessage} containing the (possibly truncated) text and a truncation flag
     */
    static ChatMessage truncateForChat(final String message) {
        final String safeMessage = message == null ? "" : message;
        final byte[] encoded = safeMessage.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= MAX_CHAT_MESSAGE_BYTES) {
            return new ChatMessage(safeMessage, false);
        }

        final int targetBytes = Math.max(0, MAX_CHAT_MESSAGE_BYTES - CHAT_TRUNCATION_SUFFIX_BYTES);
        // Walk back from targetBytes to the nearest valid UTF-8 character boundary.
        // Continuation bytes match the bit pattern 10xxxxxx (0x80â€“0xBF) and must not be split.
        int end = Math.min(targetBytes, encoded.length);
        while (end > 0 && (encoded[end] & 0xC0) == 0x80) {
            end--;
        }
        return new ChatMessage(
                new String(encoded, 0, end, StandardCharsets.UTF_8) + CHAT_TRUNCATION_SUFFIX_PADDED,
                true
        );
    }

    /**
     * Sends a (possibly truncated) feedback message to the command source.
     * If the message exceeds {@link #MAX_CHAT_MESSAGE_BYTES}, the full content is also written
     * to a timestamped file under {@link #EXPORT_DIRECTORY} and a warning is logged.
     *
     * @param source         the command source to receive the message
     * @param message        the full, un-truncated message
     * @param broadcastToOps whether to broadcast the feedback to other operators
     */
    private static void sendFeedback(
            final ServerCommandSource source, final String message,
            final boolean broadcastToOps) {
        final ChatMessage chatMessage = truncateForChat(message);
        if (chatMessage.truncated()) {
            final Path dumpPath = writeOversizedChatDump(source, message);
            Chunkis.LOGGER.warn("Chunkis debug chat output truncated; wrote full output to {}", dumpPath);
        }
        source.sendFeedback(() -> Text.literal(chatMessage.text()), broadcastToOps);
    }

    /**
     * Writes the full text of an oversized chat message to a timestamped {@code .txt} file under
     * {@link #EXPORT_DIRECTORY}. Called only when {@link #truncateForChat} returns a truncated result.
     *
     * @param source  the command source (used to resolve the world save root)
     * @param message the full message content to write
     * @return the path where the dump was written (may not exist if the write failed)
     */
    private static Path writeOversizedChatDump(final ServerCommandSource source, final String message) {
        final Path path = resolveExportPath(source, "chat-dump")
                .resolveSibling("trace-chat-dump-" + FILE_TIME_FORMAT.format(Instant.now()) + ".txt");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, message, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis debug chat dump failed: {}", e.getMessage());
        }
        return path;
    }

    /**
     * Resolves a trace export file path under {@link #EXPORT_DIRECTORY} relative to the world save root.
     * The file name is {@code trace-<scope>-<timestamp>.jsonl}.
     *
     * @param source the command source (used to locate the world save root)
     * @param scope  a short label included in the file name (e.g., {@code "latest-20"})
     * @return an absolute path suitable for writing trace output; parent directories may not yet exist
     */
    static Path resolveExportPath(final ServerCommandSource source, final String scope) {
        final Path saveRoot = source.getServer().getSavePath(WorldSavePath.ROOT);
        return saveRoot.resolve(EXPORT_DIRECTORY)
                .resolve("trace-" + scope + '-' + FILE_TIME_FORMAT.format(Instant.now()) + ".jsonl");
    }

    /**
     * Sends each event in {@code newestFirst} to {@code source} in chronological (oldest-first) order
     * by iterating the list in reverse. Does not allocate a reversed copy.
     *
     * <p>Callers are responsible for handling the empty-list case before calling this method.
     *
     * @param source      the command source to receive each formatted event line
     * @param newestFirst non-empty list of events in newest-first order
     * @return the number of events printed
     */
    private static int sendEventsOldestFirst(
            final ServerCommandSource source,
            final List<ChunkTraceEvent> newestFirst) {
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            sendFeedback(source, formatEvent(newestFirst.get(i)), false);
        }
        return newestFirst.size();
    }

    /**
     * Returns the registry key string of the world the command source is currently in.
     * Extracted to avoid repeating the same three-method call chain across all watch handlers.
     *
     * @param source the command source; must have an associated world
     * @return the world's registry key as a string (e.g., {@code "minecraft:overworld"})
     */
    private static String worldIdOf(final ServerCommandSource source) {
        return source.getWorld().getRegistryKey().getValue().toString();
    }

    /**
     * A point-in-time snapshot of the pending save/capture state for a single watched chunk.
     * Drawn simultaneously from the global chunk tracker, the async CIS save manager, and the
     * base capture scheduler to give a consistent picture of what is queued for this chunk.
     *
     * @param chunkKey           the chunk being inspected
     * @param trackerDirty       whether the global chunk tracker has a pending dirty delta for this chunk
     * @param asyncPending       the async CIS save queue entry, or {@code null} if nothing is queued
     * @param baseCapturePending the base capture scheduler entry, or {@code null} if nothing is queued
     */
    record PendingChunkSnapshot(
            DebugChunkKey chunkKey,
            boolean trackerDirty,
            AsyncCisSaveManager.PendingSaveSnapshot asyncPending,
            BaseChunkCaptureScheduler.QueuedCaptureSnapshot baseCapturePending) {}

    /**
     * The result of a {@link #truncateForChat(String)} operation.
     *
     * @param text      the final text to display in chat; may include a truncation suffix
     * @param truncated {@code true} if the original message exceeded {@link #MAX_CHAT_MESSAGE_BYTES}
     */
    record ChatMessage(String text, boolean truncated) {}
}