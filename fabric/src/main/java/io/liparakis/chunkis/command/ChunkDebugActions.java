package io.liparakis.chunkis.command;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceSuspect;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceJsonl;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Property;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Execution actions backing the Chunkis debug and trace commands.
 */
public final class ChunkDebugActions {

    /**
     * Maximum allowed byte size for a single chat message before truncation.
     */
    private static final int MAX_CHAT_MESSAGE_BYTES = 12_000;

    /**
     * Suffix text appended to truncated chat messages.
     */
    private static final String CHAT_TRUNCATION_SUFFIX = "[truncated; full trace written to log/file]";

    /**
     * Padded truncation suffix.
     */
    private static final String CHAT_TRUNCATION_SUFFIX_PADDED = " " + CHAT_TRUNCATION_SUFFIX;

    /**
     * Byte length of the padded truncation suffix.
     */
    private static final int CHAT_TRUNCATION_SUFFIX_BYTES =
            CHAT_TRUNCATION_SUFFIX_PADDED.getBytes(StandardCharsets.UTF_8).length;

    /**
     * Date/time formatter for generating trace export filenames.
     */
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC);

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkDebugActions() {
        throw new AssertionError("Utility class");
    }

    /**
     * Sets the active debug trace level.
     *
     * @param source  command execution source
     * @param level   target debug level
     * @param message success feedback message
     * @return 1 on success
     */
    public static int setLevel(final ServerCommandSource source, final ChunkisDebugLevel level, final String message) {
        ChunkisDebugConfig.setLevel(level);
        sendFeedback(source, "[Chunkis] " + message, true);
        return 1;
    }

    /**
     * Clears all stored in-memory trace events.
     *
     * @param source command execution source
     * @return 1 on success
     */
    public static int clear(final ServerCommandSource source) {
        ChunkTraceStore.clear();
        sendFeedback(source, "[Chunkis] Cleared in-memory trace events.", true);
        return 1;
    }

    /**
     * Prints the latest trace events.
     *
     * @param source command execution source
     * @param count  maximum trace count to fetch
     * @return number of events printed
     */
    public static int latest(final ServerCommandSource source, final int count) {
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latest(count);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, "[Chunkis] No trace events stored.", false);
            return 1;
        }
        return sendEventsOldestFirst(source, newestFirst);
    }

    /**
     * Lists all recorded chunk suspect markers.
     *
     * @param source command execution source
     * @return number of suspects printed
     */
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

    /**
     * Clears all chunk suspect snapshots.
     *
     * @param source command execution source
     * @return 1 on success
     */
    public static int clearSuspects(final ServerCommandSource source) {
        ChunkTraceStore.clearSuspects();
        sendFeedback(source, "[Chunkis] Cleared retained suspect snapshots.", true);
        return 1;
    }

    /**
     * Displays a detailed dump of a suspect snapshot.
     *
     * @param source    command execution source
     * @param suspectId identifier of the suspect
     * @return 1 if found, 0 if not found
     */
    public static int showSuspect(final ServerCommandSource source, final long suspectId) {
        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(suspectId);
        if (suspect == null) {
            source.sendError(Text.literal("[Chunkis] No suspect with id " + suspectId));
            return 0;
        }
        sendFeedback(source, ChunkDebugCommand.formatSuspectDetail(suspect), false);
        return 1;
    }

    /**
     * Displays a detailed dump of a suspect snapshot by coordinates.
     *
     * @param source command execution source
     * @param chunkX chunk X position
     * @param chunkZ chunk Z position
     * @return 1 if found, 0 if not found
     */
    public static int showSuspectChunk(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        final ChunkTraceSuspect suspect = ChunkTraceStore.suspect(new DebugChunkKey(chunkX, chunkZ));
        if (suspect == null) {
            source.sendError(Text.literal("[Chunkis] No suspicious trace for chunk " + chunkX + "," + chunkZ));
            return 0;
        }
        sendFeedback(source, ChunkDebugCommand.formatSuspectDetail(suspect), false);
        return 1;
    }

    /**
     * Lists the trace history timeline of a specific suspect snapshot.
     *
     * @param source    command execution source
     * @param suspectId identifier of the suspect
     * @return number of timeline events printed
     */
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
                source, "[Chunkis] Retained suspect timeline for " + suspectId + " (" + timeline.size() + " " +
                        "events)", false
        );
        for (final ChunkTraceEvent event : timeline) {
            sendFeedback(source, ChunkDebugCommand.formatEvent(event), false);
        }
        return timeline.size();
    }

    /**
     * Lists recent trace failure events.
     *
     * @param source command execution source
     * @param count  maximum entries limit
     * @return number of failures printed
     */
    public static int listFailures(final ServerCommandSource source, final int count) {
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latestFailures(count);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, "[Chunkis] No suspicious failure events stored.", false);
            return 1;
        }
        return sendEventsOldestFirst(source, newestFirst);
    }

    /**
     * Displays a trace event details page.
     *
     * @param source  command execution source
     * @param eventId identifier of the trace event
     * @return 1 if found, 0 if not found
     */
    public static int showFailure(final ServerCommandSource source, final long eventId) {
        final ChunkTraceEvent event = ChunkTraceStore.findEvent(eventId);
        if (event == null) {
            source.sendError(Text.literal("[Chunkis] No trace event with id " + eventId));
            return 0;
        }
        sendFeedback(source, ChunkDebugCommand.formatEvent(event), false);
        return 1;
    }

    /**
     * Registers a watchpoint for a specific chunk.
     *
     * @param source command execution source
     * @param chunkX chunk X position
     * @param chunkZ chunk Z position
     * @return 1 on success
     */
    public static int watchChunk(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        ChunkTraceWatchpoints.watchChunk(new DebugChunkKey(chunkX, chunkZ));
        sendFeedback(source, "[Chunkis] Watching chunk " + chunkX + "," + chunkZ, true);
        return 1;
    }

    /**
     * Registers a watchpoint for a region.
     *
     * @param source  command execution source
     * @param regionX region X position
     * @param regionZ region Z position
     * @return 1 on success
     */
    public static int watchRegion(final ServerCommandSource source, final int regionX, final int regionZ) {
        ChunkTraceWatchpoints.watchRegion(new DebugRegionKey(regionX, regionZ));
        sendFeedback(source, "[Chunkis] Watching region " + regionX + "," + regionZ, true);
        return 1;
    }

    /**
     * Registers a watchpoint for a block coordinate.
     *
     * @param source command execution source
     * @param x      block X
     * @param y      block Y
     * @param z      block Z
     * @return 1 on success
     */
    public static int watchBlock(final ServerCommandSource source, final int x, final int y, final int z) {
        final String worldId = worldIdOf(source);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block(worldId, x, y, z));
        sendFeedback(source, "[Chunkis] Watching block " + x + "," + y + "," + z + " in " + worldId, true);
        return 1;
    }

    /**
     * Registers a watchpoint for a block entity coordinate.
     *
     * @param source command execution source
     * @param x      block entity X
     * @param y      block entity Y
     * @param z      block entity Z
     * @return 1 on success
     */
    public static int watchBlockEntity(final ServerCommandSource source, final int x, final int y, final int z) {
        final String worldId = worldIdOf(source);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.blockEntity(worldId, x, y, z));
        sendFeedback(source, "[Chunkis] Watching block entity " + x + "," + y + "," + z + " in " + worldId, true);
        return 1;
    }

    /**
     * Registers a watchpoint for an entity by UUID string.
     *
     * @param source command execution source
     * @param uuid   entity UUID
     * @return 1 on success
     */
    public static int watchEntity(final ServerCommandSource source, final String uuid) {
        final String worldId = worldIdOf(source);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.entity(worldId, uuid));
        sendFeedback(source, "[Chunkis] Watching entity " + uuid + " in " + worldId, true);
        return 1;
    }

    /**
     * Clears all configured watchpoints.
     *
     * @param source command execution source
     * @return 1 on success
     */
    public static int clearWatchpoints(final ServerCommandSource source) {
        ChunkTraceWatchpoints.clear();
        sendFeedback(source, "[Chunkis] Cleared trace watchpoints.", true);
        return 1;
    }

    /**
     * Prints a summary list of all active watchpoints.
     *
     * @param source command execution source
     * @return 1 on success
     */
    public static int listWatchpoints(final ServerCommandSource source) {
        sendFeedback(source, "[Chunkis] " + ChunkDebugCommand.formatWatchpointSummary(), false);
        return 1;
    }

    /**
     * Lists the latest events matching active watchpoints.
     *
     * @param source command execution source
     * @param count  maximum count limit
     * @return number of events printed, 0 if no watchpoints configured
     */
    public static int latestWatched(final ServerCommandSource source, final int count) {
        if (ChunkTraceWatchpoints.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No watchpoints configured."));
            return 0;
        }
        final List<ChunkTraceEvent> newestFirst = ChunkTraceStore.latestMatching(count, ChunkTraceWatchpoints::matches);
        if (newestFirst.isEmpty()) {
            sendFeedback(source, ChunkDebugCommand.formatNoWatchedTraceMessage(), false);
            return 1;
        }
        return sendEventsOldestFirst(source, newestFirst);
    }

    /**
     * Prints status of pending saves or changes on watched chunks.
     *
     * @param source command execution source
     * @return number of watched chunks analyzed
     */
    public static int pendingWatched(final ServerCommandSource source) {
        final List<DebugChunkKey> watchedChunks = ChunkTraceWatchpoints.watchedChunks();
        if (watchedChunks.isEmpty()) {
            source.sendError(Text.literal("[Chunkis] No chunk watchpoints configured."));
            return 0;
        }
        final var world = source.getWorld();
        final var trackerPending = GlobalChunkTracker.getPendingDeltas(world);
        final var asyncPending = AsyncCisSaveManager.snapshot(world);
        for (final DebugChunkKey chunkKey : watchedChunks) {
            final var snapshot = new ChunkDebugCommand.PendingChunkSnapshot(
                    chunkKey,
                    trackerPending.containsKey(new ChunkPos(chunkKey.x(), chunkKey.z())), asyncPending.get(chunkKey)
            );
            sendFeedback(source, ChunkDebugCommand.formatPendingSnapshot(snapshot), false);
        }
        return watchedChunks.size();
    }

    /**
     * Prints a 3x3 inspection grid centered on the caller's current chunk.
     *
     * @param source command execution source
     * @return number of inspected chunks
     */
    public static int inspectNeighborChunks(final ServerCommandSource source) {
        final var world = source.getWorld();
        final ChunkPos center = new ChunkPos(net.minecraft.util.math.BlockPos.ofFloored(source.getPosition()));
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                final ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                sendFeedback(source, ChunkDebugCommand.formatChunkInspectSnapshot(
                        inspectChunk(world, storage, pos)
                ), false);
            }
        }
        return 9;
    }

    /**
     * Exports latest trace events to a JSONL log file.
     *
     * @param source command execution source
     * @param count  maximum count limit
     * @return number of exported events
     */
    public static int exportLatest(final ServerCommandSource source, final int count) {
        return exportEvents(source, ChunkTraceStore.snapshotMatching(event -> true), "latest-" + count, count, false);
    }

    /**
     * Exports watched trace events to a JSONL log file.
     *
     * @param source command execution source
     * @param count  maximum count limit
     * @return number of exported events
     */
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

    /**
     * Helper to slice and write events list to a JSONL destination.
     *
     * @param source      command execution source
     * @param oldestFirst snapshot list of events
     * @param scope       filename identifier scope tag
     * @param count       maximum elements to export
     * @param watched     true if filter applied
     * @return count of exported events
     */
    private static int exportEvents(
            final ServerCommandSource source, final List<ChunkTraceEvent> oldestFirst,
            final String scope, final int count, final boolean watched) {
        if (oldestFirst.isEmpty()) {
            sendFeedback(
                    source, watched ? ChunkDebugCommand.formatNoWatchedTraceMessage() : "[Chunkis] No trace " +
                                                                                        "events stored.", false
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
     * Truncates message text to fit within chat feedback length guidelines.
     *
     * @param message raw input message
     * @return ChatMessage record container
     */
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
                new String(encoded, 0, end, StandardCharsets.UTF_8) + CHAT_TRUNCATION_SUFFIX_PADDED, true);
    }

    /**
     * Truncates and transmits command feed feedback.
     *
     * @param source         command execution source
     * @param message        feedback text
     * @param broadcastToOps true to notify ops
     */
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

    /**
     * Dumps oversized chat messages to a temporary debug file.
     *
     * @param source  command execution source
     * @param message complete original message text
     * @return path to the generated dump file
     */
    private static Path writeOversizedChatDump(final ServerCommandSource source, final String message) {
        final Path path =
                resolveExportPath(source, "chat-dump").resolveSibling(
                        "trace-chat-dump-" + FILE_TIME_FORMAT.format(Instant.now()) + ".txt");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, message, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis debug chat dump failed: {}", e.getMessage());
        }
        return path;
    }

    /**
     * Generates a file path destination for trace logs exports.
     *
     * @param source command execution source
     * @param scope  filename tag label
     * @return target export Path
     */
    static Path resolveExportPath(final ServerCommandSource source, final String scope) {
        final Path saveRoot = source.getServer()
                .getSavePath(WorldSavePath.ROOT);
        return saveRoot.resolve("chunkis/debug")
                .resolve(
                        "trace-" + scope + '-' + FILE_TIME_FORMAT.format(Instant.now()) + ".jsonl");
    }

    /**
     * Transmits a list of events to feedback console sorted from oldest to newest.
     *
     * @param source      command execution source
     * @param newestFirst list of events sorted with newest at index 0
     * @return events count printed
     */
    private static int sendEventsOldestFirst(
            final ServerCommandSource source,
            final List<ChunkTraceEvent> newestFirst) {
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            sendFeedback(source, ChunkDebugCommand.formatEvent(newestFirst.get(i)), false);
        }
        return newestFirst.size();
    }

    /**
     * Helper to resolve the string identifier of the world dimension.
     *
     * @param source command execution source
     * @return registry path identifier string
     */
    private static String worldIdOf(final ServerCommandSource source) {
        return source.getWorld()
                .getRegistryKey()
                .getValue()
                .toString();
    }

    /**
     * Builds one inspection snapshot for {@code pos}.
     *
     * @param world   world to inspect
     * @param storage world storage instance
     * @param pos     chunk coordinates
     * @return inspection snapshot
     */
    @SuppressWarnings("unchecked")
    private static ChunkDebugCommand.ChunkInspectSnapshot inspectChunk(
            final net.minecraft.server.world.ServerWorld world,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos) {
        final WorldChunk liveChunk = world.getChunkManager().getWorldChunk(pos.x, pos.z, false);
        final io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> liveDelta =
                liveChunk instanceof io.liparakis.chunkis.api.ChunkisDeltaDuck duck
                        && duck.chunkis$getDelta() instanceof io.liparakis.chunkis.core.ChunkDelta<?, ?> delta
                        ? (io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound>) delta
                        : null;
        final io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> trackedDelta =
                (io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker.getDelta(world, pos);

        final boolean persistedPresent = storage.contains(FabricCisStorageHelper.toStoragePos(pos));
        io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> persistedDelta = null;
        String persistenceError = null;
        if (persistedPresent) {
            try {
                persistedDelta = storage.loadWithoutClearing(FabricCisStorageHelper.toStoragePos(pos));
            } catch (final IOException e) {
                persistenceError = e.getMessage();
            }
        }

        return new ChunkDebugCommand.ChunkInspectSnapshot(
                new DebugChunkKey(pos.x, pos.z),
                liveChunk != null,
                describeDelta(liveDelta),
                describeDelta(trackedDelta),
                describeDelta(persistedDelta),
                persistedPresent,
                persistenceError
        );
    }

    /**
     * Summarizes the restore-relevant shape of one delta.
     *
     * @param delta delta to summarize
     * @return compact summary string, or null when absent
     */
    private static String describeDelta(final io.liparakis.chunkis.core.ChunkDelta<?, NbtCompound> delta) {
        if (delta == null) {
            return null;
        }

        final Object metadata = delta.getChunkMetadata();
        return "restorable=" + ChunkDeltaOwnership.hasRestorableChunkisState(delta)
                + ", owned=" + delta.hasOwnershipClaim()
                + ", dirty=" + delta.isDirty()
                + ", blocks=" + delta.getBlockChangesCount()
                + ", sections=" + delta.getTouchedSectionCount()
                + ", hasBase=" + CisNbtUtil.hasPersistedBaseChunkNbt(metadata)
                + ", full=" + CisNbtUtil.hasFullBlockBaseline(metadata)
                + ", useBase=" + CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(metadata)
                + ", suppress=" + delta.shouldSuppressInitialRepopulation()
                + ", ver=" + delta.getSourceVersion();
    }
}
