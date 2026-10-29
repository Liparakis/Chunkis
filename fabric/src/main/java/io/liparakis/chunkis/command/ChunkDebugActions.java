package io.liparakis.chunkis.command;

import io.liparakis.chunkis.integration.migration.offline.ChunkSnapshotBuilder;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
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
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import java.io.DataInputStream;
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
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;

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
    private static final int CHAT_TRUNCATION_SUFFIX_BYTES = CHAT_TRUNCATION_SUFFIX_PADDED.getBytes(StandardCharsets.UTF_8).length;

    /**
     * Date/time formatter for generating trace export filenames.
     */
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
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
        sendFeedback(source,
                "[Chunkis] Retained suspect timeline for " + suspectId + " (" + timeline.size() + " " + "events)",
                false);
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
            final var snapshot = new ChunkDebugCommand.PendingChunkSnapshot(chunkKey,
                    trackerPending.containsKey(new ChunkPos(chunkKey.x(), chunkKey.z())),
                    asyncPending.get(chunkKey));
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
        final ChunkPos center = new ChunkPos(BlockPos.ofFloored(source.getPosition()));
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = FabricCisStorageHelper.getStorage(world);

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                final ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                sendFeedback(source,
                        ChunkDebugCommand.formatChunkInspectSnapshot(inspectChunk(world, storage, pos)),
                        false);
            }
        }
        return 9;
    }

    /**
     * Compares the caller's current chunk between raw MCA source data and the
     * persisted CIS snapshot.
     *
     * @param source command execution source
     * @return {@code 1} after emitting diagnostic output
     */
    public static int inspectCurrentChunkAgainstMca(final ServerCommandSource source) {
        final ChunkPos center = new ChunkPos(BlockPos.ofFloored(source.getPosition()));
        return inspectChunkAgainstMca(source, center.x, center.z);
    }

    /**
     * Rebuilds and overwrites the caller's current chunk directly from MCA source.
     *
     * @param source command execution source
     * @return {@code 1} on success
     */
    public static int remigrateCurrentChunkFromMca(final ServerCommandSource source) {
        final ChunkPos center = new ChunkPos(BlockPos.ofFloored(source.getPosition()));
        return remigrateChunkFromMca(source, center.x, center.z);
    }

    /**
     * Compares one chunk between raw MCA source data and the persisted CIS
     * snapshot.
     *
     * @param source command execution source
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return {@code 1} after emitting diagnostic output
     */
    public static int inspectChunkAgainstMca(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        final var world = source.getWorld();
        final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = FabricCisStorageHelper.getStorage(world);

        final ChunkDelta<BlockState, NbtCompound> cisDelta;
        try {
            cisDelta = storage.contains(FabricCisStorageHelper.toStoragePos(pos)) ? storage.loadWithoutClearing(
                    FabricCisStorageHelper.toStoragePos(pos)) : null;
        } catch (final IOException e) {
            sendFeedback(source,
                    "[Chunkis] Compare failed to load CIS chunk " + chunkX + "," + chunkZ + ": " + e.getMessage(),
                    false);
            return 0;
        }

        final NbtCompound mcaRoot;
        try {
            mcaRoot = loadMcaChunkNbt(source, pos);
        } catch (final IOException e) {
            sendFeedback(source,
                    "[Chunkis] Compare failed to read MCA chunk " + chunkX + "," + chunkZ + ": " + e.getMessage(),
                    false);
            return 0;
        }

        final ChunkDelta<BlockState, NbtCompound> mcaDelta = mcaRoot == null ? null
                : ChunkSnapshotBuilder.buildChunkDelta(world,
                        PalettesFactory.fromRegistryManager(world.getRegistryManager()),
                        mcaRoot,
                        pos);

        sendFeedback(source,
                "[Chunkis] Compare chunk=" + chunkX + "," + chunkZ + " mcaPresent=" + (mcaRoot != null) + " cisPresent="
                        + (cisDelta != null),
                false);
        sendFeedback(source, "[Chunkis] MCA  " + describeDetailedDelta(mcaDelta), false);
        sendFeedback(source, "[Chunkis] CIS  " + describeDetailedDelta(cisDelta), false);
        sendLiveChunkComparisonState(source, world, pos);

        if (mcaDelta == null || cisDelta == null) {
            return 1;
        }

        final ChunkDeltaComparison.ComparisonSummary summary = ChunkDeltaComparison.compareDeltas(mcaDelta, cisDelta);
        sendFeedback(source,
                "[Chunkis] Diff realBlocks=" + summary.realBlockMismatchCount() + ", explicitAirOnly="
                        + summary.explicitAirOnlyMismatchCount() + ", totalBlocks=" + summary.totalBlockMismatchCount()
                        + ", blockEntities=" + summary.blockEntityMismatchCount() + ", entitiesMatch="
                        + summary.entitiesMatch() + ", metadataMatch=" + summary.metadataMatch(),
                false);
        sendSampleLine(source, "Real block samples", summary.realBlockSamples());
        sendSampleLine(source, "Explicit air samples", summary.explicitAirOnlySamples());
        sendSampleLine(source, "BlockEntity samples", summary.blockEntitySamples());
        sendSampleLine(source, "Entity samples", summary.entitySamples());
        return 1;
    }

    /**
     * Performs send sample line.
     */
    private static void sendSampleLine(final ServerCommandSource source,
            final String label,
            final List<String> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        sendFeedback(source, "[Chunkis] " + label + " " + String.join(" | ", samples), false);
    }

    /**
     * Emits the current in-memory chunk and delta state that the next save would use.
     */
    private static void sendLiveChunkComparisonState(final ServerCommandSource source,
            final net.minecraft.server.world.ServerWorld world,
            final ChunkPos pos) {
        final ChunkDeltas deltas = getChunkDeltas(world, pos);
        final WorldChunk liveChunk = deltas.liveChunk();
        final ChunkDelta<BlockState, NbtCompound> attachedDelta = deltas.attachedDelta();
        final ChunkDelta<BlockState, NbtCompound> trackedDelta = deltas.trackedDelta();
        @SuppressWarnings("unchecked") final ChunkDelta<BlockState, NbtCompound> activeDelta = (ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker.getActiveDelta(
                world,
                pos);

        sendFeedback(source,
                "[Chunkis] LIVE chunkLoaded=" + (liveChunk != null) + ", blockEntities=" + (liveChunk == null ? -1
                        : liveChunk.getBlockEntities()
                          .size()),
                false);
        sendFeedback(source, "[Chunkis] LIVE attached " + describeDetailedDelta(attachedDelta), false);
        sendFeedback(source, "[Chunkis] LIVE tracked  " + describeDetailedDelta(trackedDelta), false);
        sendFeedback(source, "[Chunkis] LIVE active   " + describeDetailedDelta(activeDelta), false);
    }

    /**
     * Rebuilds one CIS chunk directly from raw MCA source and validates the write.
     *
     * @param source command execution source
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return {@code 1} on success, {@code 0} on failure
     */
    public static int remigrateChunkFromMca(final ServerCommandSource source, final int chunkX, final int chunkZ) {
        final var world = source.getWorld();
        final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = FabricCisStorageHelper.getStorage(world);

        final NbtCompound mcaRoot;
        try {
            mcaRoot = loadMcaChunkNbt(source, pos);
        } catch (final IOException e) {
            sendFeedback(source,
                    "[Chunkis] Remigrate failed to read MCA chunk " + chunkX + "," + chunkZ + ": " + e.getMessage(),
                    false);
            return 0;
        }
        if (mcaRoot == null) {
            sendFeedback(source, "[Chunkis] Remigrate skipped; no MCA source chunk " + chunkX + "," + chunkZ, false);
            return 0;
        }

        if (world.getChunkManager()
                .isChunkLoaded(chunkX, chunkZ)) {
            // The loaded chunk's live state would be saved back over the remigrated entry.
            sendFeedback(source, "[Chunkis] Remigrate refused: chunk " + chunkX + "," + chunkZ
                    + " is loaded; move away until it unloads and retry.", false);
            return 0;
        }

        final ChunkDelta<BlockState, NbtCompound> rebuilt = ChunkSnapshotBuilder.buildChunkDelta(
                world,
                PalettesFactory.fromRegistryManager(world.getRegistryManager()),
                mcaRoot,
                pos);
        final java.nio.file.Path backup = storage.backupEntry(FabricCisStorageHelper.toStoragePos(pos), "pre-remigrate");
        if (backup != null) {
            sendFeedback(source, "[Chunkis] Previous CIS entry backed up to " + backup, false);
        }
        io.liparakis.chunkis.world.entity.capture.StoredEntityFingerprints.forget(world, pos);
        if (!storage.replace(FabricCisStorageHelper.toStoragePos(pos), rebuilt)) {
            sendFeedback(source, "[Chunkis] Remigrate failed to write CIS chunk " + chunkX + "," + chunkZ, false);
            return 0;
        }

        final ChunkDelta<BlockState, NbtCompound> stored;
        try {
            stored = storage.loadWithoutClearing(FabricCisStorageHelper.toStoragePos(pos));
        } catch (final IOException e) {
            sendFeedback(source,
                    "[Chunkis] Remigrate wrote chunk but failed to read it back: " + e.getMessage(),
                    false);
            return 0;
        }
        final ChunkDeltaComparison.ComparisonSummary summary = ChunkDeltaComparison.compareDeltas(rebuilt, stored);
        if (summary.realBlockMismatchCount() != 0 || summary.blockEntityMismatchCount() != 0 || !summary.entitiesMatch()
                || !summary.metadataMatch()) {
            sendFeedback(source,
                    "[Chunkis] Remigrate wrote chunk but compare still differs realBlocks="
                            + summary.realBlockMismatchCount() + ", blockEntities=" + summary.blockEntityMismatchCount()
                            + ", entitiesMatch=" + summary.entitiesMatch() + ", metadataMatch="
                            + summary.metadataMatch(),
                    false);
            return 0;
        }

        sendFeedback(source, "[Chunkis] Remigrated chunk " + chunkX + "," + chunkZ + " from MCA.", true);
        return 1;
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
        return exportEvents(source,
                ChunkTraceStore.snapshotMatching(ChunkTraceWatchpoints::matches),
                "watched-" + count,
                count,
                true);
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
    private static int exportEvents(final ServerCommandSource source,
            final List<ChunkTraceEvent> oldestFirst,
            final String scope,
            final int count,
            final boolean watched) {
        if (oldestFirst.isEmpty()) {
            sendFeedback(source,
                    watched ? ChunkDebugCommand.formatNoWatchedTraceMessage()
                            : "[Chunkis] No trace " + "events stored.",
                    false);
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
    private static void sendFeedback(final ServerCommandSource source,
            final String message,
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
        final Path path = resolveExportPath(source, "chat-dump").resolveSibling(
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
                .resolve("trace-" + scope + '-' + FILE_TIME_FORMAT.format(Instant.now()) + ".jsonl");
    }

    /**
     * Transmits a list of events to feedback console sorted from oldest to newest.
     *
     * @param source      command execution source
     * @param newestFirst list of events sorted with newest at index 0
     * @return events count printed
     */
    private static int sendEventsOldestFirst(final ServerCommandSource source,
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
    private static ChunkDebugCommand.ChunkInspectSnapshot inspectChunk(final net.minecraft.server.world.ServerWorld world,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos pos) {
        final ChunkDeltas deltas = getChunkDeltas(world, pos);
        final WorldChunk liveChunk = deltas.liveChunk();
        final io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> liveDelta = deltas.attachedDelta();
        final io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> trackedDelta = deltas.trackedDelta();

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

        return new ChunkDebugCommand.ChunkInspectSnapshot(new DebugChunkKey(pos.x, pos.z),
                liveChunk != null,
                describeDelta(liveDelta),
                describeDelta(trackedDelta),
                describeDelta(persistedDelta),
                persistedPresent,
                persistenceError);
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
        return "restorable=" + ChunkDeltaOwnership.hasRestorableChunkisState(delta) + ", owned="
                + delta.hasOwnershipClaim() + ", dirty=" + delta.isDirty() + ", blocks=" + delta.getBlockChangesCount()
                + ", sections=" + delta.getTouchedSectionCount() + ", hasBase=" + CisNbtUtil.hasPersistedBaseChunkNbt(
                metadata) + ", full=" + CisNbtUtil.hasFullBlockBaseline(metadata) + ", useBase="
                + CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(metadata) + ", suppress="
                + delta.shouldSuppressInitialRepopulation() + ", ver=" + delta.getSourceVersion();
    }

    /**
     * Summarizes one delta with the fields most relevant to MCA-vs-CIS
     * migration diagnostics.
     *
     * @param delta delta to summarize
     * @return one-line summary string
     */
    private static String describeDetailedDelta(final io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> delta) {
        if (delta == null) {
            return "<none>";
        }

        final NbtCompound metadata = delta.getChunkMetadata() instanceof NbtCompound compound ? compound : null;
        final NbtCompound auxiliary = metadata == null ? null : CisNbtUtil.extractPreservedAuxiliaryChunkNbt(metadata);
        return describeDelta(delta) + ", blockEntities=" + delta.getBlockEntities()
                .size() + ", pendingEntities=" + delta.countPendingEntities() + ", migrated="
                + CisNbtUtil.isMigratedAuthoritativeChunk(metadata) + ", auxKeys=" + (auxiliary == null ? 0
                : auxiliary.getKeys()
                  .size());
    }

    /**
     * Loads the raw MCA chunk NBT for one chunk, if a source chunk still exists.
     *
     * @param source command execution source
     * @param pos    chunk coordinates
     * @return raw chunk NBT, or {@code null} when the source MCA chunk is absent
     * @throws IOException if reading fails
     */
    private static NbtCompound loadMcaChunkNbt(final ServerCommandSource source, final ChunkPos pos)
            throws IOException {
        final Path regionDir = ChunkisStoragePaths.computeVanillaRegionDirectory(source.getServer()
                        .getSavePath(WorldSavePath.ROOT),
                source.getWorld()
                        .getRegistryKey());
        final String baseName = "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
        final Path livePath = regionDir.resolve(baseName);
        final Path backupPath = regionDir.resolve(baseName + ".backup");
        final Path mcaPath;
        if (Files.exists(livePath)) {
            mcaPath = livePath;
        } else if (Files.exists(backupPath)) {
            mcaPath = backupPath;
        } else {
            return null;
        }

        final StorageKey storageKey = new StorageKey("chunk",
                source.getWorld()
                        .getRegistryKey(),
                "chunk");
        try (RegionFile regionFile = new RegionFile(storageKey, mcaPath, mcaPath.getParent(), true);
                DataInputStream input = regionFile.getChunkInputStream(pos)) {
            if (input == null) {
                return null;
            }
            return NbtIo.readCompound(input, NbtSizeTracker.ofUnlimitedBytes());
        }
    }

    /**
     * Performs suppress warnings.
     */
    @SuppressWarnings("unchecked")
    private static ChunkDeltas getChunkDeltas(final ServerWorld world, final ChunkPos pos) {
        final WorldChunk liveChunk = world.getChunkManager()
                .getWorldChunk(pos.x, pos.z, false);
        final ChunkDelta<BlockState, NbtCompound> attachedDelta =
                liveChunk instanceof ChunkisDeltaDuck duck && duck.chunkis$getDelta() instanceof ChunkDelta<?, ?> delta
                        ? (ChunkDelta<BlockState, NbtCompound>) delta : null;
        final ChunkDelta<BlockState, NbtCompound> trackedDelta = (ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker.getDelta(
                world,
                pos);
        return new ChunkDeltas(liveChunk, attachedDelta, trackedDelta);
    }

    /**
     * Container for live chunk and delta state.
     *
     * @param liveChunk     live chunk reference, may be null
     * @param attachedDelta delta attached to the live chunk, may be null
     * @param trackedDelta  delta currently tracked in the global tracker, may be null
     */
    private record ChunkDeltas(WorldChunk liveChunk, ChunkDelta<BlockState, NbtCompound> attachedDelta,
                               ChunkDelta<BlockState, NbtCompound> trackedDelta) {

    }
}
