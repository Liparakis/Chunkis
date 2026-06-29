package io.liparakis.chunkis.command;

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
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureScheduler;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
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

public final class ChunkDebugActions {

    private static final int MAX_CHAT_MESSAGE_BYTES = 12_000;
    private static final String CHAT_TRUNCATION_SUFFIX = "[truncated; full trace written to log/file]";
    private static final String CHAT_TRUNCATION_SUFFIX_PADDED = " " + CHAT_TRUNCATION_SUFFIX;
    private static final int CHAT_TRUNCATION_SUFFIX_BYTES =
            CHAT_TRUNCATION_SUFFIX_PADDED.getBytes(StandardCharsets.UTF_8).length;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private ChunkDebugActions() {
        throw new AssertionError("Utility class");
    }

    public static int setLevel(
            final ServerCommandSource source, final ChunkisDebugLevel level,
            final String message) {
        ChunkisDebugConfig.setLevel(level);
        sendFeedback(source, "[Chunkis] " + message, true);
        return 1;
    }

    public static int clear(final ServerCommandSource source) {
        ChunkTraceStore.clear();
        sendFeedback(source, "[Chunkis] Cleared in-memory trace events.", true);
        return 1;
    }

    public static int latest(final ServerCommandSource source, final int count) {
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latest(count);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, "[Chunkis] No trace events stored.", false);
            return 1;
        }
        return sendEventsOldestFirst(source, newestFirst);
    }

    public static int listSuspects(final ServerCommandSource source) {
        final List<ChunkTraceSuspect> suspects = ChunkTraceStore.suspects();
        if (suspects.isEmpty()) {
            sendFeedback(source, "[Chunkis] No suspicious chunks recorded.", false);
            return 1;
        }
        for (final ChunkTraceSuspect suspect : suspects) {
            sendFeedback(source, ChunkDebugCommand.formatSuspectSummary(suspect), false);
        }
        return suspects.size();
    }

    public static int clearSuspects(final ServerCommandSource source) {
        ChunkTraceStore.clearSuspects();
        sendFeedback(source, "[Chunkis] Cleared retained suspect snapshots.", true);
        return 1;
    }

    public static int showSuspect(final ServerCommandSource source, final long suspectId) {
        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(suspectId);
        if (suspect == null) {
            source.sendError(Text.literal("[Chunkis] No suspect with id " + suspectId));
            return 0;
        }
        sendFeedback(source, ChunkDebugCommand.formatSuspectDetail(suspect), false);
        return 1;
    }

    public static int showSuspectChunk(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(new DebugChunkKey(chunkX, chunkZ));
        if (suspect == null) {
            source.sendError(Text.literal("[Chunkis] No suspicious trace for chunk " + chunkX + "," + chunkZ));
            return 0;
        }
        sendFeedback(source, ChunkDebugCommand.formatSuspectDetail(suspect), false);
        return 1;
    }

    public static int showSuspectTimeline(final ServerCommandSource source, final long suspectId) {
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
            sendFeedback(source, ChunkDebugCommand.formatEvent(event), false);
        }
        return timeline.size();
    }

    public static int listFailures(final ServerCommandSource source, final int count) {
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latestFailures(count);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, "[Chunkis] No suspicious failure events stored.", false);
            return 1;
        }
        return sendEventsOldestFirst(source, newestFirst);
    }

    public static int showFailure(final ServerCommandSource source, final long eventId) {
        final ChunkTraceEvent event = ChunkTraceStore.findEvent(eventId);
        if (event == null) {
            source.sendError(Text.literal("[Chunkis] No trace event with id " + eventId));
            return 0;
        }
        sendFeedback(source, ChunkDebugCommand.formatEvent(event), false);
        return 1;
    }

    public static int watchChunk(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        ChunkTraceWatchpoints.watchChunk(new DebugChunkKey(chunkX, chunkZ));
        sendFeedback(source, "[Chunkis] Watching chunk " + chunkX + "," + chunkZ, true);
        return 1;
    }

    public static int watchRegion(final ServerCommandSource source, final int regionX, final int regionZ) {
        ChunkTraceWatchpoints.watchRegion(new DebugRegionKey(regionX, regionZ));
        sendFeedback(source, "[Chunkis] Watching region " + regionX + "," + regionZ, true);
        return 1;
    }

    public static int watchBlock(final ServerCommandSource source, final int x, final int y, final int z) {
        final String worldId = worldIdOf(source);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block(worldId, x, y, z));
        sendFeedback(source, "[Chunkis] Watching block " + x + "," + y + "," + z + " in " + worldId, true);
        return 1;
    }

    public static int watchBlockEntity(final ServerCommandSource source, final int x, final int y, final int z) {
        final String worldId = worldIdOf(source);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.blockEntity(worldId, x, y, z));
        sendFeedback(source, "[Chunkis] Watching block entity " + x + "," + y + "," + z + " in " + worldId, true);
        return 1;
    }

    public static int watchEntity(final ServerCommandSource source, final String uuid) {
        final String worldId = worldIdOf(source);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.entity(worldId, uuid));
        sendFeedback(source, "[Chunkis] Watching entity " + uuid + " in " + worldId, true);
        return 1;
    }

    public static int clearWatchpoints(final ServerCommandSource source) {
        ChunkTraceWatchpoints.clear();
        sendFeedback(source, "[Chunkis] Cleared trace watchpoints.", true);
        return 1;
    }

    public static int listWatchpoints(final ServerCommandSource source) {
        sendFeedback(source, "[Chunkis] " + ChunkDebugCommand.formatWatchpointSummary(), false);
        return 1;
    }

    public static int latestWatched(final ServerCommandSource source, final int count) {
        if (ChunkTraceWatchpoints.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No watchpoints configured."));
            return 0;
        }
        final List<ChunkTraceEvent> newestFirst =
                ChunkTraceStore.latestMatching(count, ChunkTraceWatchpoints::matches);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, ChunkDebugCommand.formatNoWatchedTraceMessage(), false);
            return 1;
        }
        return sendEventsOldestFirst(source, newestFirst);
    }

    public static int pendingWatched(final ServerCommandSource source) {
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
            final var snapshot = new ChunkDebugCommand.PendingChunkSnapshot(
                    chunkKey,
                    trackerPending.containsKey(new ChunkPos(chunkKey.x(), chunkKey.z())),
                    asyncPending.get(chunkKey),
                    basePending.get(chunkKey)
            );
            sendFeedback(source, ChunkDebugCommand.formatPendingSnapshot(snapshot), false);
        }
        return watchedChunks.size();
    }

    public static int exportLatest(final ServerCommandSource source, final int count) {
        return exportEvents(
                source, ChunkTraceStore.snapshotMatching(event -> true),
                "latest-" + count, count, false
        );
    }

    public static int exportWatched(final ServerCommandSource source, final int count) {
        if (ChunkTraceWatchpoints.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No watchpoints configured."));
            return 0;
        }
        return exportEvents(
                source, ChunkTraceStore.snapshotMatching(ChunkTraceWatchpoints::matches),
                "watched-" + count, count, true
        );
    }

    private static int exportEvents(
            final ServerCommandSource source, final List<ChunkTraceEvent> oldestFirst,
            final String scope, final int count, final boolean watched) {
        if (oldestFirst.isEmpty()) {
            sendFeedback(
                    source,
                    watched ? ChunkDebugCommand.formatNoWatchedTraceMessage() : "[Chunkis] No trace events stored.",
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

    public static ChunkDebugCommand.ChatMessage truncateForChat(final String message) {
        final String safeMessage = message == null ? "" : message;
        final byte[] encoded = safeMessage.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= MAX_CHAT_MESSAGE_BYTES) {
            return new ChunkDebugCommand.ChatMessage(safeMessage, false);
        }

        final int targetBytes = Math.max(0, MAX_CHAT_MESSAGE_BYTES - CHAT_TRUNCATION_SUFFIX_BYTES);
        int end = Math.min(targetBytes, encoded.length);
        while (end > 0 && (encoded[end] & 0xC0) == 0x80) {
            end--;
        }
        return new ChunkDebugCommand.ChatMessage(
                new String(encoded, 0, end, StandardCharsets.UTF_8) + CHAT_TRUNCATION_SUFFIX_PADDED,
                true
        );
    }

    private static void sendFeedback(
            final ServerCommandSource source, final String message,
            final boolean broadcastToOps) {
        final ChunkDebugCommand.ChatMessage chatMessage = truncateForChat(message);
        if (chatMessage.truncated()) {
            final Path dumpPath = writeOversizedChatDump(source, message);
            Chunkis.LOGGER.warn("Chunkis debug chat output truncated; wrote full output to {}", dumpPath);
        }
        source.sendFeedback(() -> Text.literal(chatMessage.text()), broadcastToOps);
    }

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

    static Path resolveExportPath(final ServerCommandSource source, final String scope) {
        final Path saveRoot = source.getServer().getSavePath(WorldSavePath.ROOT);
        return saveRoot.resolve("chunkis/debug")
                .resolve("trace-" + scope + '-' + FILE_TIME_FORMAT.format(Instant.now()) + ".jsonl");
    }

    private static int sendEventsOldestFirst(
            final ServerCommandSource source,
            final List<ChunkTraceEvent> newestFirst) {
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            sendFeedback(source, ChunkDebugCommand.formatEvent(newestFirst.get(i)), false);
        }
        return newestFirst.size();
    }

    private static String worldIdOf(final ServerCommandSource source) {
        return source.getWorld().getRegistryKey().getValue().toString();
    }
}
