package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.ChunkisStoragePaths;
import io.liparakis.chunkis.storage.CisNbtUtil;
import io.liparakis.chunkis.storage.FabricCisStorageHelper;
import io.liparakis.chunkis.storage.io.CisStorage;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports physical and logical Chunkis storage usage for one world/dimension.
 *
 * <p>The command walks raw CIS region files to measure on-disk bytes and header
 * occupancy, then decodes stored chunks through normal storage helpers to detect
 * metadata such as persisted base NBT usage.</p>
 */
public final class StorageReportCommand {

    /**
     * Matches a Chunkis region file name and captures region X/Z coordinates.
     */
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cis");
    /**
     * Size of one region header entry: offset + length.
     */
    private static final int HEADER_ENTRY_BYTES = 8;
    /**
     * Number of chunk slots in one 32x32 region file.
     */
    private static final int HEADER_SLOTS = 1024;
    /**
     * Total bytes reserved for one region header.
     */
    private static final int HEADER_BYTES = HEADER_ENTRY_BYTES * HEADER_SLOTS;
    /**
     * Default number of largest regions shown in the command output.
     */
    private static final int DEFAULT_TOP_REGIONS = 8;
    /**
     * Binary kibibyte unit used for human-readable output.
     */
    private static final long ONE_KIB = 1024L;
    /**
     * Binary mebibyte unit used for human-readable output.
     */
    private static final long ONE_MIB = ONE_KIB * 1024L;

    private StorageReportCommand() {
        throw new AssertionError("Utility class");
    }

    /**
     * Registers the {@code /chunkis_storage_report} command.
     */
    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("chunkis_storage_report")
                        .requires(source -> source.getPermissions()
                                .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
                        .executes(context -> run(context, DEFAULT_TOP_REGIONS))
                        .then(CommandManager.argument("topRegions", IntegerArgumentType.integer(1, 32))
                                .executes(context -> run(
                                        context,
                                        IntegerArgumentType.getInteger(context, "topRegions")
                                )))
        );
    }

    /**
     * Executes the storage report and sends the formatted result to the caller.
     */
    private static int run(final CommandContext<ServerCommandSource> context, final int topRegions) {
        final ServerCommandSource source = context.getSource();
        final ServerWorld world = source.getWorld();

        try {
            final StorageReport report = inspectWorld(world);
            sendReport(source, world, report, topRegions);
            return 1;
        } catch (final IOException e) {
            source.sendError(Text.literal("[Chunkis] Storage report failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Scans one world/dimension and aggregates all region-level storage metrics.
     */
    private static StorageReport inspectWorld(final ServerWorld world) throws IOException {
        final Path saveRoot = Objects.requireNonNull(world.getServer()).getSavePath(WorldSavePath.ROOT);
        final Path chunkisDir = ChunkisStoragePaths.computeRegionsDirectory(saveRoot, world.getRegistryKey());
        final Path vanillaDir = ChunkisStoragePaths.computeVanillaRegionDirectory(saveRoot, world.getRegistryKey());

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);

        final List<RegionReport> regions = new ArrayList<>();
        long totalChunkisBytes = 0L;
        long totalLiveBytes = 0L;
        long totalSlackBytes = 0L;
        long totalVanillaBytes = sumFiles(vanillaDir);
        int totalStoredChunks = 0;
        int totalBaseChunks = 0;

        if (Files.isDirectory(chunkisDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(chunkisDir, "r.*.*.cis")) {
                for (final Path regionPath : stream) {
                    final RegionReport region = inspectRegion(storage, regionPath);
                    regions.add(region);
                    totalChunkisBytes += region.fileBytes();
                    totalLiveBytes += region.liveBytes();
                    totalSlackBytes += region.slackBytes();
                    totalStoredChunks += region.storedChunks();
                    totalBaseChunks += region.baseChunks();
                }
            }
        }

        regions.sort(Comparator.comparingLong(RegionReport::liveBytes).reversed());

        return new StorageReport(
                chunkisDir,
                vanillaDir,
                totalChunkisBytes,
                totalLiveBytes,
                totalSlackBytes,
                totalVanillaBytes,
                totalStoredChunks,
                totalBaseChunks,
                regions
        );
    }

    /**
     * Reads one raw CIS region file and computes its live/slack usage.
     */
    private static RegionReport inspectRegion(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final Path regionPath
    ) throws IOException {
        final Matcher matcher = REGION_FILE_PATTERN.matcher(regionPath.getFileName().toString());
        if (!matcher.matches()) {
            throw new IOException("Unexpected region filename: " + regionPath.getFileName());
        }

        final int regionX = Integer.parseInt(matcher.group(1));
        final int regionZ = Integer.parseInt(matcher.group(2));
        final long fileBytes = Files.size(regionPath);

        long liveBytes = 0L;
        int storedChunks = 0;
        int baseChunks = 0;

        try (FileChannel channel = FileChannel.open(regionPath, StandardOpenOption.READ)) {
            final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            readFully(channel, header);
            header.flip();

            for (int slot = 0; slot < HEADER_SLOTS; slot++) {
                final int offset = header.getInt();
                final int length = header.getInt();

                if (offset <= 0 || length <= 0) {
                    continue;
                }

                storedChunks++;
                liveBytes += length;

                final CisChunkPos pos = new CisChunkPos(
                        (regionX << 5) + (slot & 31),
                        (regionZ << 5) + (slot >>> 5)
                );

                final ChunkDelta<BlockState, NbtCompound> delta = storage.loadWithoutClearing(pos);
                if (CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata())) {
                    baseChunks++;
                }
            }
        }

        final long compactedBytes = HEADER_BYTES + liveBytes;
        final long slackBytes = Math.max(0L, fileBytes - compactedBytes);

        return new RegionReport(
                regionPath.getFileName().toString(),
                fileBytes,
                liveBytes,
                slackBytes,
                storedChunks,
                baseChunks
        );
    }

    /**
     * Renders the collected report in a compact operator-facing format.
     */
    private static void sendReport(
            final ServerCommandSource source,
            final ServerWorld world,
            final StorageReport report,
            final int topRegions
    ) {
        final int baseFreeChunks = report.storedChunks() - report.baseChunks();
        final double baseShare = report.storedChunks() == 0
                ? 0.0
                : (100.0 * report.baseChunks()) / report.storedChunks();
        final double liveVsVanilla = report.vanillaBytes() == 0L
                ? 0.0
                : (100.0 * report.liveBytes()) / report.vanillaBytes();

        source.sendFeedback(
                () -> Text.literal(
                        "[Chunkis] Storage report for " + world.getRegistryKey().getValue()), false
        );

        source.sendFeedback(
                () -> Text.literal(
                        "  Chunkis bytes: " + formatBytes(report.chunkisBytes())
                                + " | live: " + formatBytes(report.liveBytes())
                                + " | slack: " + formatBytes(report.slackBytes())), false
        );

        source.sendFeedback(
                () -> Text.literal(
                        "  Stored chunks: " + report.storedChunks()
                                + " | with base NBT: " + report.baseChunks()
                                + " (" + formatPercent(baseShare) + ")"
                                + " | sparse-only: " + baseFreeChunks), false
        );

        source.sendFeedback(
                () -> Text.literal(
                        "  Vanilla region bytes: " + formatBytes(report.vanillaBytes())
                                + " | Chunkis live / vanilla: " + formatPercent(liveVsVanilla)), false
        );

        source.sendFeedback(
                () -> Text.literal(
                        "  Chunkis dir: " + report.chunkisDir()), false
        );

        source.sendFeedback(
                () -> Text.literal(
                        "  Vanilla dir: " + report.vanillaDir()), false
        );

        final int limit = Math.min(topRegions, report.regions().size());
        if (limit == 0) {
            source.sendFeedback(() -> Text.literal("  No Chunkis region files found."), false);
            return;
        }

        source.sendFeedback(() -> Text.literal("  Top " + limit + " regions by live bytes:"), false);
        for (int i = 0; i < limit; i++) {
            final RegionReport region = report.regions().get(i);
            source.sendFeedback(
                    () -> Text.literal(
                            "    " + region.name()
                                    + " | live " + formatBytes(region.liveBytes())
                                    + " | slack " + formatBytes(region.slackBytes())
                                    + " | stored " + region.storedChunks()
                                    + " | base " + region.baseChunks()), false
            );
        }
    }

    /**
     * Sums the size of all files in {@code dir} matching {@code glob}.
     */
    private static long sumFiles(final Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }

        long total = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.mca")) {
            for (final Path path : stream) {
                total += Files.size(path);
            }
        }
        return total;
    }

    /**
     * Reads exactly {@code buffer.remaining()} bytes or fails with EOF.
     */
    private static void readFully(final FileChannel channel, final ByteBuffer buffer)
            throws IOException {
        long current = 0L;
        while (buffer.hasRemaining()) {
            final int read = channel.read(buffer, current);
            if (read < 0) {
                throw new IOException("Unexpected EOF while reading " + channel);
            }
            current += read;
        }
    }

    /**
     * Formats raw bytes using binary units for command output.
     */
    private static String formatBytes(final long bytes) {
        if (bytes >= ONE_MIB) {
            return String.format("%.2f MiB", bytes / (double) ONE_MIB);
        }
        if (bytes >= ONE_KIB) {
            return String.format("%.2f KiB", bytes / (double) ONE_KIB);
        }
        return bytes + " B";
    }

    /**
     * Formats a percentage with one decimal place.
     */
    private static String formatPercent(final double value) {
        return String.format("%.1f%%", value);
    }

    /**
     * Immutable top-level storage report for one world/dimension.
     */
    private record StorageReport(
            Path chunkisDir,
            Path vanillaDir,
            long chunkisBytes,
            long liveBytes,
            long slackBytes,
            long vanillaBytes,
            int storedChunks,
            int baseChunks,
            List<RegionReport> regions
    ) {
    }

    /**
     * Immutable per-region breakdown used for sorting and display.
     */
    private record RegionReport(
            String name,
            long fileBytes,
            long liveBytes,
            long slackBytes,
            int storedChunks,
            int baseChunks
    ) {
    }
}
