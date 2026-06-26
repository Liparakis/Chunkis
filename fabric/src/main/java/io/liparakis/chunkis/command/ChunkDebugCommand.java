package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceJsonl;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkTraceSuspect;
import io.liparakis.chunkis.debug.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.debug.DebugRegionKey;
import io.liparakis.chunkis.debug.PayloadWatchTarget;
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
import java.util.ArrayList;
import java.util.List;

public final class ChunkDebugCommand {

    private static final int MAX_LATEST_COUNT = 200;
    private static final int DEFAULT_FAILURE_COUNT = 20;
    private static final String EXPORT_DIRECTORY = "chunkis/debug";
    private static final int MAX_CHAT_MESSAGE_BYTES = 12_000;
    private static final String CHAT_TRUNCATION_SUFFIX = "[truncated; full trace written to log/file]";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private ChunkDebugCommand() {
        throw new AssertionError("Utility class");
    }

    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        final var watchCommand =
                CommandManager.literal("watch").then(CommandManager.literal("chunk").then(CommandManager.argument("x"
                        , IntegerArgumentType.integer()).then(CommandManager.argument("z",
                        IntegerArgumentType.integer()).executes(context -> watchChunk(context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "z")))))).then(CommandManager.literal("region").then(CommandManager.argument("x", IntegerArgumentType.integer()).then(CommandManager.argument("z", IntegerArgumentType.integer()).executes(context -> watchRegion(context.getSource(), IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "z")))))).then(CommandManager.literal("block").then(CommandManager.argument("x", IntegerArgumentType.integer()).then(CommandManager.argument("y", IntegerArgumentType.integer()).then(CommandManager.argument("z", IntegerArgumentType.integer()).executes(context -> watchBlock(context.getSource(), IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"))))))).then(CommandManager.literal("blockentity").then(CommandManager.argument("x", IntegerArgumentType.integer()).then(CommandManager.argument("y", IntegerArgumentType.integer()).then(CommandManager.argument("z", IntegerArgumentType.integer()).executes(context -> watchBlockEntity(context.getSource(), IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"))))))).then(CommandManager.literal("entity").then(CommandManager.argument("uuid", UuidArgumentType.uuid()).executes(context -> watchEntity(context.getSource(), UuidArgumentType.getUuid(context, "uuid").toString())))).then(CommandManager.literal("clear").executes(context -> clearWatchpoints(context.getSource()))).then(CommandManager.literal("list").executes(context -> listWatchpoints(context.getSource()))).then(CommandManager.literal("pending").executes(context -> pendingWatched(context.getSource()))).then(CommandManager.literal("latest").then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT)).executes(context -> latestWatched(context.getSource(), IntegerArgumentType.getInteger(context, "count")))));

        final var debugCommand =
                CommandManager.literal("debug").then(CommandManager.literal("on").executes(context -> setLevel(context.getSource(), ChunkisDebugLevel.LIFECYCLE, "Chunkis debug set to LIFECYCLE"))).then(CommandManager.literal("off").executes(context -> setLevel(context.getSource(), ChunkisDebugLevel.OFF, "Chunkis debug disabled"))).then(CommandManager.literal("clear").executes(context -> clear(context.getSource()))).then(CommandManager.literal("latest").then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT)).executes(context -> latest(context.getSource(), IntegerArgumentType.getInteger(context, "count"))))).then(CommandManager.literal("export").then(CommandManager.literal("latest").then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT)).executes(context -> exportLatest(context.getSource(), IntegerArgumentType.getInteger(context, "count"))))).then(CommandManager.literal("watched").then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT)).executes(context -> exportWatched(context.getSource(), IntegerArgumentType.getInteger(context, "count")))))).then(CommandManager.literal("suspects").executes(context -> listSuspects(context.getSource())).then(CommandManager.literal("clear").executes(context -> clearSuspects(context.getSource())))).then(CommandManager.literal("suspect").then(CommandManager.argument("suspectId", LongArgumentType.longArg(1L)).executes(context -> showSuspect(context.getSource(), LongArgumentType.getLong(context, "suspectId")))).then(CommandManager.literal("timeline").then(CommandManager.argument("suspectId", LongArgumentType.longArg(1L)).executes(context -> showSuspectTimeline(context.getSource(), LongArgumentType.getLong(context, "suspectId"))))).then(CommandManager.literal("chunk").then(CommandManager.argument("x", IntegerArgumentType.integer()).then(CommandManager.argument("z", IntegerArgumentType.integer()).executes(context -> showSuspectChunk(context.getSource(), IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "z"))))))).then(CommandManager.literal("failures").executes(context -> listFailures(context.getSource(), DEFAULT_FAILURE_COUNT)).then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_LATEST_COUNT)).executes(context -> listFailures(context.getSource(), IntegerArgumentType.getInteger(context, "count"))))).then(CommandManager.literal("failure").then(CommandManager.argument("eventId", LongArgumentType.longArg(1L)).executes(context -> showFailure(context.getSource(), LongArgumentType.getLong(context, "eventId"))))).then(watchCommand);

        dispatcher.register(CommandManager.literal("chunkis").requires(source -> source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS))).then(debugCommand));
    }

    static String formatEvent(final ChunkTraceEvent event) {
        final StringBuilder builder = new StringBuilder(192);
        builder.append('#').append(event.eventId()).append(' ').append(TIME_FORMAT.format(Instant.ofEpochMilli(event.timestampMillis()))).append(" UTC ").append(event.eventType()).append(' ').append(event.severity()).append('/').append(event.reason()).append(" [").append(event.domain()).append(']');

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
        if (event.payloadWatchTarget() != null) {
            builder.append(" payload=").append(event.payloadWatchTarget().describe());
        }
        if (event.payloadWatchStage() != null) {
            builder.append(" stage=").append(event.payloadWatchStage());
        }
        if (event.payloadWatchSummary() != null) {
            builder.append(" payloadSummary=").append(event.payloadWatchSummary());
        }

        builder.append(" src=").append(event.source());
        builder.append(" thread=").append(event.threadName());
        builder.append(" msg=").append(event.message());
        return builder.toString();
    }

    private static int setLevel(final ServerCommandSource source, final ChunkisDebugLevel level, final String message) {
        ChunkisDebugConfig.setLevel(level);
        sendFeedback(source, "[Chunkis] " + message, true);
        return 1;
    }

    private static int clear(final ServerCommandSource source) {
        ChunkTraceStore.clear();
        sendFeedback(source, "[Chunkis] Cleared in-memory trace events.", true);
        return 1;
    }

    private static int latest(final ServerCommandSource source, final int count) {
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latest(count);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, "[Chunkis] No trace events stored.", false);
            return 1;
        }

        final List<ChunkTraceEvent> oldestFirst = new ArrayList<>(newestFirst);
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            final ChunkTraceEvent event = oldestFirst.get(i);
            sendFeedback(source, formatEvent(event), false);
        }
        return oldestFirst.size();
    }

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

    private static int clearSuspects(final ServerCommandSource source) {
        ChunkTraceStore.clearSuspects();
        sendFeedback(source, "[Chunkis] Cleared retained suspect snapshots.", true);
        return 1;
    }

    private static int showSuspect(final ServerCommandSource source, final long suspectId) {
        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(suspectId);
        if (suspect == null) {
            source.sendError(Text.literal("[Chunkis] No suspect with id " + suspectId));
            return 0;
        }

        sendFeedback(source, formatSuspectDetail(suspect), false);
        return 1;
    }

    private static int showSuspectChunk(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(new DebugChunkKey(chunkX, chunkZ));
        if (suspect == null) {
            source.sendError(Text.literal("[Chunkis] No suspicious trace for chunk " + chunkX + "," + chunkZ));
            return 0;
        }

        sendFeedback(source, formatSuspectDetail(suspect), false);
        return 1;
    }

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

        sendFeedback(source, "[Chunkis] Retained suspect timeline for " + suspectId + " (" + timeline.size() + " events)", false);
        for (final ChunkTraceEvent event : timeline) {
            sendFeedback(source, formatEvent(event), false);
        }
        return timeline.size();
    }

    private static int listFailures(final ServerCommandSource source, final int count) {
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latestFailures(count);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, "[Chunkis] No suspicious failure events stored.", false);
            return 1;
        }

        final List<ChunkTraceEvent> oldestFirst = new ArrayList<>(newestFirst);
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            final ChunkTraceEvent event = oldestFirst.get(i);
            sendFeedback(source, formatEvent(event), false);
        }
        return oldestFirst.size();
    }

    private static int showFailure(final ServerCommandSource source, final long eventId) {
        final ChunkTraceEvent event = ChunkTraceStore.findEvent(eventId);
        if (event == null) {
            source.sendError(Text.literal("[Chunkis] No trace event with id " + eventId));
            return 0;
        }

        sendFeedback(source, formatEvent(event), false);
        return 1;
    }

    private static int watchChunk(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        ChunkTraceWatchpoints.watchChunk(new DebugChunkKey(chunkX, chunkZ));
        sendFeedback(source, "[Chunkis] Watching chunk " + chunkX + "," + chunkZ, true);
        return 1;
    }

    private static int watchRegion(final ServerCommandSource source, final int regionX, final int regionZ) {
        ChunkTraceWatchpoints.watchRegion(new DebugRegionKey(regionX, regionZ));
        sendFeedback(source, "[Chunkis] Watching region " + regionX + "," + regionZ, true);
        return 1;
    }

    private static int watchBlock(final ServerCommandSource source, final int x, final int y, final int z) {
        final String worldId = source.getWorld().getRegistryKey().getValue().toString();
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block(worldId, x, y, z));
        sendFeedback(source, "[Chunkis] Watching block " + x + "," + y + "," + z + " in " + worldId, true);
        return 1;
    }

    private static int watchBlockEntity(final ServerCommandSource source, final int x, final int y, final int z) {
        final String worldId = source.getWorld().getRegistryKey().getValue().toString();
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.blockEntity(worldId, x, y, z));
        sendFeedback(source, "[Chunkis] Watching block entity " + x + "," + y + "," + z + " in " + worldId, true);
        return 1;
    }

    private static int watchEntity(final ServerCommandSource source, final String uuid) {
        final String worldId = source.getWorld().getRegistryKey().getValue().toString();
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.entity(worldId, uuid));
        sendFeedback(source, "[Chunkis] Watching entity " + uuid + " in " + worldId, true);
        return 1;
    }

    private static int clearWatchpoints(final ServerCommandSource source) {
        ChunkTraceWatchpoints.clear();
        sendFeedback(source, "[Chunkis] Cleared trace watchpoints.", true);
        return 1;
    }

    private static int listWatchpoints(final ServerCommandSource source) {
        final String summary = formatWatchpointSummary();
        sendFeedback(source, "[Chunkis] " + summary, false);
        return 1;
    }

    private static int latestWatched(final ServerCommandSource source, final int count) {
        if (ChunkTraceWatchpoints.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No watchpoints configured."));
            return 0;
        }

        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latestMatching(count, ChunkTraceWatchpoints::matches);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, "[Chunkis] No watched trace events stored.", false);
            return 1;
        }

        final List<ChunkTraceEvent> oldestFirst = new ArrayList<>(newestFirst);
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            final ChunkTraceEvent event = oldestFirst.get(i);
            sendFeedback(source, formatEvent(event), false);
        }
        return oldestFirst.size();
    }

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
            final PendingChunkSnapshot snapshot = new PendingChunkSnapshot(chunkKey,
                    trackerPending.containsKey(new ChunkPos(chunkKey.x(), chunkKey.z())), asyncPending.get(chunkKey),
                    basePending.get(chunkKey));
            sendFeedback(source, formatPendingSnapshot(snapshot), false);
        }
        return watchedChunks.size();
    }

    private static int exportLatest(final ServerCommandSource source, final int count) {
        return exportEvents(source, ChunkTraceStore.snapshotMatching(event -> true), "latest-" + count, count, false);
    }

    private static int exportWatched(final ServerCommandSource source, final int count) {
        if (ChunkTraceWatchpoints.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No watchpoints configured."));
            return 0;
        }

        return exportEvents(source, ChunkTraceStore.snapshotMatching(ChunkTraceWatchpoints::matches),
                "watched-" + count, count, true);
    }

    private static int exportEvents(final ServerCommandSource source, final List<ChunkTraceEvent> oldestFirst,
                                    final String scope, final int count, final boolean watched) {
        if (oldestFirst.isEmpty()) {
            sendFeedback(source, watched ? "[Chunkis] No watched trace events stored." : "[Chunkis] No trace events stored.", false);
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

        sendFeedback(source, "[Chunkis] Exported " + exportEvents.size() + " trace events to " + exportPath, true);
        return exportEvents.size();
    }

    static ChatMessage truncateForChat(final String message) {
        final String safeMessage = message == null ? "" : message;
        if (utf8Length(safeMessage) <= MAX_CHAT_MESSAGE_BYTES) {
            return new ChatMessage(safeMessage, false);
        }

        final int suffixBytes = utf8Length(" " + CHAT_TRUNCATION_SUFFIX);
        final int targetBytes = Math.max(0, MAX_CHAT_MESSAGE_BYTES - suffixBytes);
        int end = safeMessage.length();
        while (end > 0 && utf8Length(safeMessage.substring(0, end)) > targetBytes) {
            end--;
        }
        return new ChatMessage(safeMessage.substring(0, end) + " " + CHAT_TRUNCATION_SUFFIX, true);
    }

    private static void sendFeedback(
            final ServerCommandSource source,
            final String message,
            final boolean broadcastToOps
    ) {
        final ChatMessage chatMessage = truncateForChat(message);
        if (chatMessage.truncated()) {
            final Path dumpPath = writeOversizedChatDump(source, message);
            Chunkis.LOGGER.warn("Chunkis debug chat output truncated; wrote full output to {}", dumpPath);
        }
        source.sendFeedback(() -> Text.literal(chatMessage.text()), broadcastToOps);
    }

    private static Path writeOversizedChatDump(
            final ServerCommandSource source,
            final String message
    ) {
        final Path path = resolveExportPath(source, "chat-dump").resolveSibling(
                "trace-chat-dump-" + FILE_TIME_FORMAT.format(Instant.now()) + ".txt"
        );
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, message, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis debug chat dump failed: {}", e.getMessage());
        }
        return path;
    }

    private static int utf8Length(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static Path resolveExportPath(final ServerCommandSource source, final String scope) {
        final Path saveRoot = source.getServer().getSavePath(WorldSavePath.ROOT);
        return saveRoot.resolve(EXPORT_DIRECTORY).resolve("trace-" + scope + '-' + FILE_TIME_FORMAT.format(Instant.now()) + ".jsonl");
    }

    static String formatWatchpointSummary() {
        final List<DebugChunkKey> chunks = ChunkTraceWatchpoints.watchedChunks();
        final List<DebugRegionKey> regions = ChunkTraceWatchpoints.watchedRegions();
        final List<PayloadWatchTarget> payloads = ChunkTraceWatchpoints.watchedPayloads();
        if (chunks.isEmpty() && regions.isEmpty() && payloads.isEmpty()) {
            return "No trace watchpoints configured.";
        }

        final StringBuilder builder = new StringBuilder("Watchpoints:");
        if (!chunks.isEmpty()) {
            builder.append(" chunks=").append(formatChunks(chunks));
        }
        if (!regions.isEmpty()) {
            builder.append(" regions=").append(formatRegions(regions));
        }
        if (!payloads.isEmpty()) {
            builder.append(" payloads=").append(formatPayloads(payloads));
        }
        return builder.toString();
    }

    static String formatPendingSnapshot(final PendingChunkSnapshot snapshot) {
        final StringBuilder builder = new StringBuilder(128);
        builder.append("chunk=").append(snapshot.chunkKey().x()).append(',').append(snapshot.chunkKey().z()).append(
                " trackerDirty=").append(snapshot.trackerDirty()).append(" asyncQueued=").append(snapshot.asyncPending() != null).append(" baseCaptureQueued=").append(snapshot.baseCapturePending() != null);

        if (snapshot.asyncPending() != null) {
            builder.append(" asyncOp=").append(snapshot.asyncPending().operationId()).append(" asyncGeneration=").append(snapshot.asyncPending().generation()).append(" asyncDirty=").append(snapshot.asyncPending().dirtyState());
        }
        if (snapshot.baseCapturePending() != null) {
            builder.append(" baseCaptureDirty=").append(snapshot.baseCapturePending().dirtyState());
        }
        return builder.toString();
    }

    static String formatSuspectSummary(final ChunkTraceSuspect suspect) {
        final StringBuilder builder = new StringBuilder(160);
        builder.append("id=").append(suspect.suspectId()).append(" chunk=").append(suspect.chunkKey().x()).append(',').append(suspect.chunkKey().z());
        if (suspect.regionKey() != null) {
            builder.append(" region=").append(suspect.regionKey().x()).append(',').append(suspect.regionKey().z());
        }
        builder.append(" reason=").append(suspect.reason()).append(" latest=").append(suspect.latestEvent().eventType()).append('#').append(suspect.latestEvent().eventId()).append(" severity=").append(suspect.severity()).append(" occurrences=").append(suspect.occurrenceCount());
        return builder.toString();
    }

    static String formatSuspectDetail(final ChunkTraceSuspect suspect) {
        final StringBuilder builder = new StringBuilder(384);
        builder.append("suspectId=").append(suspect.suspectId()).append(" chunk=").append(suspect.chunkKey().x()).append(',').append(suspect.chunkKey().z());
        if (suspect.regionKey() != null) {
            builder.append(" region=").append(suspect.regionKey().x()).append(',').append(suspect.regionKey().z());
        }
        builder.append(" reason=").append(suspect.reason()).append(" original=").append(suspect.originalFailureEvent().eventType()).append('#').append(suspect.originalFailureEvent().eventId()).append(" latest=").append(suspect.latestEvent().eventType()).append('#').append(suspect.latestEvent().eventId()).append(" severity=").append(suspect.severity()).append(" firstSeen=").append(TIME_FORMAT.format(Instant.ofEpochMilli(suspect.firstSeenTimestampMillis()))).append(" UTC lastSeen=").append(TIME_FORMAT.format(Instant.ofEpochMilli(suspect.lastSeenTimestampMillis()))).append(" UTC occurrences=").append(suspect.occurrenceCount());
        if (suspect.operationId() != null) {
            builder.append(" op=").append(suspect.operationId());
        }
        builder.append(" timeline=").append(suspect.copiedTimeline().size()).append(" latestMsg=").append(suspect.latestMessage()).append(" inspect=/chunkis debug suspect timeline ").append(suspect.suspectId());
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

    private static String formatPayloads(final List<PayloadWatchTarget> payloads) {
        final List<String> parts = new ArrayList<>(payloads.size());
        for (final PayloadWatchTarget payload : payloads) {
            parts.add(payload.describe());
        }
        return String.join(" | ", parts);
    }

    record PendingChunkSnapshot(DebugChunkKey chunkKey, boolean trackerDirty,
                                AsyncCisSaveManager.PendingSaveSnapshot asyncPending,
                                BaseChunkCaptureScheduler.QueuedCaptureSnapshot baseCapturePending) {}

    record ChatMessage(String text, boolean truncated) {}
}
