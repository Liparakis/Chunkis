package io.liparakis.chunkis.command;

import com.github.luben.zstd.Zstd;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.storage.bits.BitReader;
import io.liparakis.chunkis.storage.io.region.CisRegionInspector;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.model.CisChunk;
import io.liparakis.chunkis.storage.model.CisConstants;
import io.liparakis.chunkis.storage.model.CisSection;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.StringJoiner;
import java.util.TreeMap;
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
     * Number of blocks in one Minecraft section.
     */
    private static final int SECTION_BLOCKS = 4096;
    /**
     * Bit width of an in-section block coordinate in CIS sparse encodings.
     */
    private static final int SPARSE_ENTRY_POSITION_BITS = 12;

    /**
     * Default number of largest regions shown in the command output.
     */
    private static final int DEFAULT_TOP_REGIONS = 8;
    /**
     * Fixed number of largest chunks shown in the command output.
     */
    private static final int TOP_CHUNKS = 10;
    /**
     * Fixed number of largest dense sections shown in the command output.
     */
    private static final int TOP_DENSE_SECTIONS = 20;

    /**
     * Binary kibibyte unit used for human-readable output.
     */
    private static final long ONE_KIB = 1024L;
    /**
     * Binary mebibyte unit used for human-readable output.
     */
    private static final long ONE_MIB = ONE_KIB * 1024L;

    private static final Comparator<RegionReport> REGION_BY_LIVE_BYTES =
            Comparator.comparingLong(RegionReport::liveBytes)
                    .thenComparing(RegionReport::name);

    private static final Comparator<ChunkReport> CHUNK_BY_LIVE_BYTES =
            Comparator.comparingLong(ChunkReport::liveBytes)
                    .thenComparing(report -> report.pos().toString());

    private static final Comparator<DenseSectionReport> DENSE_SECTION_BY_ENCODED_BYTES =
            Comparator.comparingLong(DenseSectionReport::encodedBytes)
                    .thenComparing(report -> report.pos().toString())
                    .thenComparingInt(DenseSectionReport::sectionY);

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
            final StorageReport report = inspectWorld(world, topRegions);
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
    private static StorageReport inspectWorld(final ServerWorld world, final int topRegions) throws IOException {
        final Path saveRoot = Objects.requireNonNull(world.getServer()).getSavePath(WorldSavePath.ROOT);
        final Path chunkisDir = ChunkisStoragePaths.computeRegionsDirectory(saveRoot, world.getRegistryKey());
        final Path vanillaDir = ChunkisStoragePaths.computeVanillaRegionDirectory(saveRoot, world.getRegistryKey());

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);

        final Map<String, CisRegionInspector.RegionSpaceUsage> regionSpaceByName =
                indexRegionSpace(CisRegionInspector.inspect(chunkisDir));

        final WorldScanAccumulator accumulator = new WorldScanAccumulator(
                chunkisDir,
                vanillaDir,
                sumFiles(vanillaDir),
                topRegions
        );

        if (!Files.isDirectory(chunkisDir)) {
            return accumulator.toReport();
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(chunkisDir, "r.*.*.cis")) {
            for (final Path regionPath : stream) {
                accumulator.addRegion(inspectRegion(
                        storage,
                        regionPath,
                        regionSpaceByName.get(regionPath.getFileName().toString())
                ));
            }
        }

        return accumulator.toReport();
    }

    /**
     * Builds a filename-indexed view of reusable-space diagnostics returned by the storage inspector.
     */
    private static Map<String, CisRegionInspector.RegionSpaceUsage> indexRegionSpace(
            final Iterable<CisRegionInspector.RegionSpaceUsage> usages
    ) {
        final Map<String, CisRegionInspector.RegionSpaceUsage> byName = new HashMap<>();
        for (final CisRegionInspector.RegionSpaceUsage usage : usages) {
            byName.put(usage.name(), usage);
        }
        return byName;
    }

    /**
     * Reads one raw CIS region file and computes its live/slack usage.
     */
    private static RegionInspection inspectRegion(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final Path regionPath,
            final CisRegionInspector.RegionSpaceUsage regionSpace
    ) throws IOException {
        final RegionCoordinates coordinates = RegionFileReader.parseCoordinates(regionPath);
        final long physicalBytes = regionSpace == null ? Files.size(regionPath) : regionSpace.physicalBytes();
        final RegionScanAccumulator accumulator = new RegionScanAccumulator(
                regionPath.getFileName().toString(),
                physicalBytes,
                regionSpace
        );

        try (FileChannel channel = FileChannel.open(regionPath, StandardOpenOption.READ)) {
            final long channelBytes = channel.size();
            final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            readFully(channel, header);
            header.flip();

            for (int slot = 0; slot < HEADER_SLOTS; slot++) {
                final int offset = header.getInt();
                final int length = header.getInt();

                if (offset <= 0 || length <= 0) {
                    continue;
                }

                RegionFileReader.validateChunkRange(regionPath, offset, length, channelBytes);
                final CisChunkPos pos = new CisChunkPos(
                        (coordinates.x() << 5) + (slot & 31),
                        (coordinates.z() << 5) + (slot >>> 5)
                );

                final byte[] compressed = RegionFileReader.readChunkBytes(channel, offset, length);
                final byte[] raw = RegionFileReader.decompressChunkPayload(compressed);
                final ChunkPayloadDiagnostics payload = inspectChunkPayload(raw);

                final ChunkDelta<BlockState, NbtCompound> delta = storage.loadWithoutClearing(pos);
                final CisChunk<BlockState> encoderChunk = buildEncoderChunk(delta);
                final EncoderInputDiagnostics encoderInput = inspectEncoderInput(encoderChunk);
                final DenseSectionAnalysis denseAnalysis = inspectDenseSections(pos, payload, encoderChunk);

                accumulator.addChunk(
                        pos,
                        length,
                        payload,
                        denseAnalysis,
                        encoderInput,
                        CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata())
                );
            }
        }

        return accumulator.toInspection();
    }

    /**
     * Parses a raw CIS chunk payload and reports which section encodings it uses
     * plus the encoded bit cost of each section kind.
     */
    static ChunkPayloadDiagnostics inspectChunkPayload(final byte[] raw) throws IOException {
        if (raw.length < 8) {
            throw new IOException("CIS chunk payload too small: " + raw.length);
        }

        final PayloadCursor cursor = new PayloadCursor(raw);
        final int magic = cursor.readInt("magic");
        if (magic != CisConstants.MAGIC) {
            throw new IOException("Invalid CIS magic");
        }

        final int version = cursor.readInt("version");
        if (version < 7 || version > CisConstants.VERSION) {
            throw new IOException("Unsupported CIS version " + version);
        }

        final int globalPaletteSize = cursor.readNonNegativeInt("global palette size");
        cursor.skip(checkedByteCount(globalPaletteSize, 2, "global palette"), "global palette");

        final int propertyBytes = cursor.readNonNegativeInt("property table length");
        cursor.skip(propertyBytes, "property table");

        final int sectionCount = cursor.readUnsignedShort("section count");
        final int sectionDataLength = cursor.readNonNegativeInt("section data length");
        final byte[] sectionData = cursor.readBytes(sectionDataLength, "section data");

        final ChunkSectionPayloadAccumulator sections = inspectSectionPayloads(
                sectionData,
                sectionCount,
                version,
                calculateBitsNeeded(globalPaletteSize)
        );

        final int blockEntities = cursor.readNonNegativeInt("block entity count");
        final ChunkEncodingKind kind = chunkEncodingKind(
                sections.sawUniform(),
                sections.sawDefaultSparse(),
                sections.sawSparse(),
                sections.sawDense()
        );

        return new ChunkPayloadDiagnostics(
                sectionCount,
                sections.uniformSections(),
                sections.defaultSparseSections(),
                sections.sparseSections(),
                sections.denseSections(),
                blockEntities,
                sections.uniformBits(),
                sections.defaultSparseBits(),
                sections.sparseBits(),
                sections.denseBits(),
                sections.globalBits(),
                sections.sectionReports(),
                kind
        );
    }

    /**
     * Inspects an already-built encoder snapshot. This avoids rebuilding the same logical chunk
     * when both dense and uniform diagnostics are requested during region scanning.
     */
    private static EncoderInputDiagnostics inspectEncoderInput(final CisChunk<BlockState> chunk) {
        int uniformCandidateSections = 0;
        int fullUniformCandidateSections = 0;
        int implicitAirRejectedUniformSections = 0;
        int sanityRejectedUniformSections = 0;

        for (int i = 0; i < chunk.getSectionCount(); i++) {
            final int sectionY = chunk.getSortedSectionIndex(i);
            final CisSection<BlockState> section = chunk.getSections().get(sectionY);
            if (section == null || section.mode == CisSection.MODE_EMPTY) {
                continue;
            }

            final SectionUniformDiagnostics diagnostics = inspectSectionUniform(section);
            if (!diagnostics.singleExplicitState()) {
                continue;
            }

            uniformCandidateSections++;
            if (diagnostics.explicitBlockCount() == SECTION_BLOCKS) {
                if (section.mode == CisSection.MODE_DENSE) {
                    fullUniformCandidateSections++;
                } else {
                    // A sparse section with all positions explicitly filled should normally have become dense.
                    sanityRejectedUniformSections++;
                }
            } else {
                // Sparse sections can look uniform among explicit blocks, but implicit air makes them non-uniform.
                implicitAirRejectedUniformSections++;
            }
        }

        return new EncoderInputDiagnostics(
                uniformCandidateSections,
                fullUniformCandidateSections,
                implicitAirRejectedUniformSections,
                sanityRejectedUniformSections
        );
    }

    /**
     * Rebuilds the encoder-facing chunk snapshot used by the uniform and dense
     * diagnostics.
     */
    private static CisChunk<BlockState> buildEncoderChunk(final ChunkDelta<BlockState, NbtCompound> delta) {
        final CisChunk<BlockState> chunk = new CisChunk<>();
        delta.forEachBlock((x, y, z, state) -> {
            if (state != null && !state.isAir()) {
                chunk.addUniqueBlock(x, y, z, state);
            }
        });
        return chunk;
    }

    /**
     * Computes additional diagnostics for every dense section, including palette
     * distributions and estimated byte margins against competing encodings.
     */
    private static DenseSectionAnalysis inspectDenseSections(
            final CisChunkPos pos,
            final ChunkPayloadDiagnostics payload,
            final CisChunk<BlockState> chunk
    ) {
        final Map<Integer, Integer> paletteSizes = new TreeMap<>();
        final Map<Integer, Integer> bitsPerBlock = new TreeMap<>();
        final BoundedTopList<DenseSectionReport> largestSections =
                new BoundedTopList<>(TOP_DENSE_SECTIONS, DENSE_SECTION_BY_ENCODED_BYTES);
        final DenseComparisonAccumulator comparison = new DenseComparisonAccumulator();

        for (final SectionPayloadDiagnostics sectionPayload : payload.sections()) {
            if (sectionPayload.kind() != SectionEncodingKind.DENSE) {
                continue;
            }

            final CisSection<BlockState> section = chunk.getSections().get(sectionPayload.sectionY());
            if (section == null) {
                continue;
            }

            incrementCount(paletteSizes, sectionPayload.localPaletteSize());
            incrementCount(bitsPerBlock, sectionPayload.bitsPerBlock());

            final int sparseEntries = sparseEntryCount(section);
            final long denseBytes = bitsToBytes(sectionPayload.encodedBits());
            final long sparseBytes = bitsToBytes(1L + CisConstants.BLOCK_COUNT_BITS
                    + ((long) sparseEntries * (SPARSE_ENTRY_POSITION_BITS + payload.globalBits())));

            final DefaultSparseReport defaultSparse = defaultSparseReport(section);
            final long defaultSparseBytes = defaultSparse == null
                    ? 0L
                    : bitsToBytes(1L + CisConstants.BLOCK_COUNT_BITS + payload.globalBits()
                                  + CisConstants.BLOCK_COUNT_BITS
                                  + ((long) defaultSparse.exceptionCount()
                                     * (SPARSE_ENTRY_POSITION_BITS + payload.globalBits())));

            final long sparseMargin = sparseBytes - denseBytes;
            final long defaultSparseMargin = defaultSparseBytes - denseBytes;
            comparison.record(sparseMargin, defaultSparseMargin, defaultSparse != null);

            largestSections.add(new DenseSectionReport(
                    pos,
                    sectionPayload.sectionY(),
                    denseBytes,
                    sectionPayload.localPaletteSize(),
                    sectionPayload.bitsPerBlock(),
                    sparseMargin,
                    defaultSparseMargin,
                    topLogicalStates(section, 3)
            ));
        }

        return new DenseSectionAnalysis(
                Map.copyOf(paletteSizes),
                Map.copyOf(bitsPerBlock),
                largestSections.snapshotDescending(),
                comparison.samples(),
                comparison.denseBeatsSparseCount(),
                comparison.denseVsSparseMarginTotal(),
                comparison.denseVsSparseWorstMargin(),
                comparison.denseBeatsDefaultSparseCount(),
                comparison.denseVsDefaultSparseMarginTotal(),
                comparison.denseVsDefaultSparseWorstMargin()
        );
    }

    /**
     * Reads and classifies section payloads from the packed bitstream.
     */
    private static ChunkSectionPayloadAccumulator inspectSectionPayloads(
            final byte[] sectionData,
            final int sectionCount,
            final int version,
            final int globalBits
    ) throws IOException {
        final BitReader reader = new BitReader(sectionData);
        final ChunkSectionPayloadAccumulator accumulator =
                new ChunkSectionPayloadAccumulator(sectionCount, globalBits);
        final int paletteBits = (version == 7) ? 8 : CisConstants.PALETTE_SIZE_BITS;

        for (int i = 0; i < sectionCount; i++) {
            final int sectionY = readZigZag(reader, CisConstants.SECTION_Y_BITS, "section y");
            final int mode = (int) readBits(reader, 1, "section encoding mode");

            if (mode == CisConstants.SECTION_ENCODING_SPARSE) {
                inspectSparseLikeSection(reader, accumulator, sectionY, globalBits);
            } else {
                inspectDensePayloadSection(reader, accumulator, sectionY, globalBits, paletteBits);
            }
        }

        return accumulator;
    }

    /**
     * Parses sparse, default-sparse, and uniform sentinels from one encoded section.
     */
    private static void inspectSparseLikeSection(
            final BitReader reader,
            final ChunkSectionPayloadAccumulator accumulator,
            final int sectionY,
            final int globalBits
    ) throws IOException {
        final int blockCount = (int) readBits(reader, CisConstants.BLOCK_COUNT_BITS, "sparse block count");

        if (blockCount == CisConstants.UNIFORM_SECTION_SENTINEL) {
            readBits(reader, globalBits, "uniform state");
            accumulator.addUniform(sectionY, 1L + CisConstants.BLOCK_COUNT_BITS + globalBits);
            return;
        }

        if (blockCount == CisConstants.DEFAULT_SPARSE_SECTION_SENTINEL) {
            readBits(reader, globalBits, "default sparse state");
            final int exceptionCount = (int) readBits(
                    reader,
                    CisConstants.BLOCK_COUNT_BITS,
                    "default sparse exception count"
            );
            skipSparseEntries(reader, exceptionCount, globalBits, "default sparse exception");
            accumulator.addDefaultSparse(sectionY, exceptionCount);
            return;
        }

        skipSparseEntries(reader, blockCount, globalBits, "sparse entry");
        accumulator.addSparse(sectionY, blockCount);
    }

    /**
     * Parses one dense section payload and records its encoded size.
     */
    private static void inspectDensePayloadSection(
            final BitReader reader,
            final ChunkSectionPayloadAccumulator accumulator,
            final int sectionY,
            final int globalBits,
            final int paletteBits
    ) throws IOException {
        final int localSize = (int) readBits(reader, paletteBits, "local palette size");
        for (int entry = 0; entry < localSize; entry++) {
            readBits(reader, globalBits, "local palette entry");
        }

        final int bitsPerBlock = calculateBitsNeeded(localSize + 1);
        for (int block = 0; block < SECTION_BLOCKS; block++) {
            readBits(reader, bitsPerBlock, "dense block palette index");
        }

        accumulator.addDense(sectionY, localSize, bitsPerBlock, paletteBits);
    }

    /**
     * Skips position/state pairs used by sparse-style encodings.
     */
    private static void skipSparseEntries(
            final BitReader reader,
            final int count,
            final int globalBits,
            final String label
    ) throws IOException {
        for (int entry = 0; entry < count; entry++) {
            readBits(reader, SPARSE_ENTRY_POSITION_BITS, label + " position");
            readBits(reader, globalBits, label + " state");
        }
    }

    /**
     * Determines whether one logical section contains exactly one explicit state
     * and how many positions are explicitly populated.
     */
    private static SectionUniformDiagnostics inspectSectionUniform(final CisSection<BlockState> section) {
        if (section.mode == CisSection.MODE_SPARSE) {
            return inspectSparseUniform(section);
        }

        return inspectDenseUniform(section);
    }

    private static SectionUniformDiagnostics inspectSparseUniform(final CisSection<BlockState> section) {
        if (section.sparseSize <= 0) {
            return new SectionUniformDiagnostics(0, false);
        }

        final BlockState first = (BlockState) section.sparseValues[0];
        for (int i = 1; i < section.sparseSize; i++) {
            if (!Objects.equals(first, section.sparseValues[i])) {
                return new SectionUniformDiagnostics(section.sparseSize, false);
            }
        }
        return new SectionUniformDiagnostics(section.sparseSize, true);
    }

    private static SectionUniformDiagnostics inspectDenseUniform(final CisSection<BlockState> section) {
        int explicitBlockCount = 0;
        BlockState first = null;
        for (final Object rawState : section.denseBlocks) {
            if (rawState == null) {
                continue;
            }

            final BlockState state = (BlockState) rawState;
            if (first == null) {
                first = state;
            } else if (!Objects.equals(first, state)) {
                return new SectionUniformDiagnostics(explicitBlockCount + 1, false);
            }
            explicitBlockCount++;
        }

        return new SectionUniformDiagnostics(explicitBlockCount, explicitBlockCount > 0);
    }

    /**
     * Counts explicit non-default entries in the logical section representation.
     */
    private static int sparseEntryCount(final CisSection<BlockState> section) {
        if (section.mode == CisSection.MODE_SPARSE) {
            return section.sparseSize;
        }

        int count = 0;
        for (final Object state : section.denseBlocks) {
            if (state != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Estimates default-sparse exception count by choosing the most common
     * logical state across all 4096 positions.
     */
    private static DefaultSparseReport defaultSparseReport(final CisSection<BlockState> section) {
        final Map<BlockState, Integer> counts = new HashMap<>();
        int defaultCount = 0;

        if (section.mode == CisSection.MODE_SPARSE) {
            defaultCount = SECTION_BLOCKS - section.sparseSize;
            for (int i = 0; i < section.sparseSize; i++) {
                final BlockState state = (BlockState) section.sparseValues[i];
                defaultCount = Math.max(defaultCount, counts.merge(state, 1, Integer::sum));
            }
        } else {
            int airCount = 0;
            for (final Object rawState : section.denseBlocks) {
                final BlockState state = (BlockState) rawState;
                if (state == null) {
                    airCount++;
                    continue;
                }
                defaultCount = Math.max(defaultCount, counts.merge(state, 1, Integer::sum));
            }
            defaultCount = Math.max(defaultCount, airCount);
        }

        final int exceptionCount = SECTION_BLOCKS - defaultCount;
        if (exceptionCount <= 0) {
            return null;
        }

        return new DefaultSparseReport(exceptionCount);
    }

    /**
     * Summarizes the most common logical states in a section for operator-facing
     * dense diagnostics.
     */
    private static String topLogicalStates(final CisSection<BlockState> section, final int limit) {
        if (limit <= 0) {
            return "n/a";
        }

        final Map<String, Integer> counts = new HashMap<>();
        if (section.mode == CisSection.MODE_SPARSE) {
            counts.put("air", SECTION_BLOCKS - section.sparseSize);
            for (int i = 0; i < section.sparseSize; i++) {
                incrementStringCount(counts, String.valueOf(section.sparseValues[i]));
            }
        } else {
            for (final Object rawState : section.denseBlocks) {
                final BlockState state = (BlockState) rawState;
                incrementStringCount(counts, state == null ? "air" : String.valueOf(state));
            }
        }

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer> comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("n/a");
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
        ReportRenderer.send(source, world, report, topRegions);
    }

    /**
     * Adds counts from one histogram into another.
     */
    private static void mergeCounts(final Map<Integer, Integer> target, final Map<Integer, Integer> source) {
        for (final Map.Entry<Integer, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    /**
     * Increments one integer histogram bucket.
     */
    private static void incrementCount(final Map<Integer, Integer> counts, final int key) {
        counts.merge(key, 1, Integer::sum);
    }

    /**
     * Increments one string histogram bucket.
     */
    private static void incrementStringCount(final Map<String, Integer> counts, final String key) {
        counts.merge(key, 1, Integer::sum);
    }

    /**
     * Rounds an encoded bit count up to whole bytes.
     */
    private static long bitsToBytes(final long bits) {
        return (bits + 7L) / 8L;
    }

    /**
     * Returns a zero-safe integer average.
     */
    private static long safeAverage(final long total, final int count) {
        if (count <= 0) {
            return 0L;
        }
        return total / count;
    }

    /**
     * Formats a histogram as {@code valuexcount} pairs for command output.
     */
    private static String formatDistribution(final Map<Integer, Integer> distribution) {
        if (distribution.isEmpty()) {
            return "n/a";
        }

        final StringJoiner joiner = new StringJoiner(", ");
        distribution.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> joiner.add(entry.getKey() + "x" + entry.getValue()));
        return joiner.toString();
    }

    /**
     * Formats a signed byte delta with binary units.
     */
    private static String formatSignedBytes(final long bytes) {
        if (bytes > 0L) {
            return "+" + formatBytes(bytes);
        }
        if (bytes < 0L) {
            return "-" + formatBytes(Math.abs(bytes));
        }
        return "0 B";
    }

    /**
     * Collapses per-section booleans into one chunk-level encoding label.
     */
    private static ChunkEncodingKind chunkEncodingKind(
            final boolean sawUniform,
            final boolean sawDefaultSparse,
            final boolean sawSparse,
            final boolean sawDense
    ) {
        final int kinds = (sawUniform ? 1 : 0)
                + (sawDefaultSparse ? 1 : 0)
                + (sawSparse ? 1 : 0)
                + (sawDense ? 1 : 0);
        if (kinds == 0) {
            return ChunkEncodingKind.EMPTY;
        }
        if (kinds > 1) {
            return ChunkEncodingKind.MIXED;
        }
        if (sawDense) {
            return ChunkEncodingKind.DENSE_ONLY;
        }
        if (sawSparse) {
            return ChunkEncodingKind.SPARSE_ONLY;
        }
        if (sawDefaultSparse) {
            return ChunkEncodingKind.DEFAULT_SPARSE_ONLY;
        }
        return ChunkEncodingKind.UNIFORM_ONLY;
    }

    /**
     * Sums the size of all vanilla region files in {@code dir}.
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
        readFully(channel, buffer, 0L);
    }

    /**
     * Reads exactly {@code buffer.remaining()} bytes from an absolute file
     * position or fails with EOF.
     */
    private static void readFully(final FileChannel channel, final ByteBuffer buffer, final long position)
            throws IOException {
        long current = position;
        while (buffer.hasRemaining()) {
            final int read = channel.read(buffer, current);
            if (read < 0) {
                throw new IOException("Unexpected EOF while reading " + channel);
            }
            if (read == 0) {
                throw new IOException("Read made no progress while reading " + channel);
            }
            current += read;
        }
    }

    /**
     * Returns a zero-safe byte average.
     */
    private static long averageBytes(final long totalBytes, final int count) {
        if (count <= 0) {
            return 0L;
        }
        return totalBytes / count;
    }

    /**
     * Returns average encoded bytes per section from a summed bit count.
     */
    private static long averageSectionBytes(final long totalBits, final int count) {
        if (count <= 0) {
            return 0L;
        }
        return bitsToBytes(totalBits) / count;
    }

    /**
     * Calculates the minimum number of bits required to encode values in the
     * range {@code [0, maxValue)}.
     */
    private static int calculateBitsNeeded(final int maxValue) {
        if (maxValue <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(maxValue - 1);
    }

    /**
     * Formats raw bytes using binary units for command output.
     */
    private static String formatBytes(final long bytes) {
        if (bytes >= ONE_MIB) {
            return String.format(Locale.ROOT, "%.2f MiB", bytes / (double) ONE_MIB);
        }
        if (bytes >= ONE_KIB) {
            return String.format(Locale.ROOT, "%.2f KiB", bytes / (double) ONE_KIB);
        }
        return bytes + " B";
    }

    /**
     * Formats a percentage with one decimal place.
     */
    private static String formatPercent(final double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /**
     * Converts raw slack bytes into a physical-size percentage.
     */
    private static double slackPercent(final long physicalBytes, final long slackBytes) {
        if (physicalBytes <= 0L) {
            return 0.0;
        }
        return (100.0 * slackBytes) / physicalBytes;
    }

    private static int checkedByteCount(final int count, final int bytesPerEntry, final String field)
            throws IOException {
        if (count < 0) {
            throw new IOException("Negative " + field + " count: " + count);
        }

        final long bytes = (long) count * bytesPerEntry;
        if (bytes > Integer.MAX_VALUE) {
            throw new IOException(field + " is too large: " + bytes + " bytes");
        }
        return (int) bytes;
    }

    private static long readBits(final BitReader reader, final int bits, final String field) throws IOException {
        if (bits == 0) {
            return 0L;
        }

        try {
            return reader.read(bits);
        } catch (final RuntimeException e) {
            throw new IOException("Malformed CIS section bitstream while reading " + field, e);
        }
    }

    private static int readZigZag(final BitReader reader, final int bits, final String field) throws IOException {
        try {
            return reader.readZigZag(bits);
        } catch (final RuntimeException e) {
            throw new IOException("Malformed CIS section bitstream while reading " + field, e);
        }
    }

    /**
     * Mutable world-level aggregation. Keeping counters here prevents the scan loop from becoming
     * a long chain of unrelated local variables.
     */
    private static final class WorldScanAccumulator {
        private final Path chunkisDir;
        private final Path vanillaDir;
        private final long vanillaBytes;
        private final BoundedTopList<RegionReport> largestRegions;
        private final BoundedTopList<ChunkReport> largestChunks =
                new BoundedTopList<>(TOP_CHUNKS, CHUNK_BY_LIVE_BYTES);
        private final BoundedTopList<DenseSectionReport> largestDenseSections =
                new BoundedTopList<>(TOP_DENSE_SECTIONS, DENSE_SECTION_BY_ENCODED_BYTES);
        private final SectionEncodingTotals sectionTotals = new SectionEncodingTotals();
        private final ChunkMixCounters chunkMix = new ChunkMixCounters();
        private final DenseComparisonAccumulator denseComparison = new DenseComparisonAccumulator();
        private final Map<Integer, Integer> densePaletteSizes = new TreeMap<>();
        private final Map<Integer, Integer> denseBitsPerBlock = new TreeMap<>();

        private long chunkisBytes;
        private long liveBytes;
        private long slackBytes;
        private long reusableBytes;
        private long reuseHits;
        private long reuseMisses;
        private int storedChunks;
        private int baseChunks;
        private int freeBlocks;
        private int largestFreeBlock;
        private int blockEntities;
        private int uniformCandidateSections;
        private int fullUniformCandidateSections;
        private int implicitAirRejectedUniformSections;
        private int sanityRejectedUniformSections;

        private WorldScanAccumulator(
                final Path chunkisDir,
                final Path vanillaDir,
                final long vanillaBytes,
                final int topRegions
        ) {
            this.chunkisDir = chunkisDir;
            this.vanillaDir = vanillaDir;
            this.vanillaBytes = vanillaBytes;
            this.largestRegions = new BoundedTopList<>(topRegions, REGION_BY_LIVE_BYTES);
        }

        private void addRegion(final RegionInspection inspection) {
            final RegionReport report = inspection.regionReport();

            largestRegions.add(report);
            largestChunks.addAll(inspection.chunkReports());
            largestDenseSections.addAll(inspection.denseSectionReports());

            chunkisBytes += report.fileBytes();
            liveBytes += report.liveBytes();
            slackBytes += report.slackBytes();
            storedChunks += report.storedChunks();
            baseChunks += report.baseChunks();
            reusableBytes += report.reusableBytes();
            freeBlocks += report.freeBlockCount();
            largestFreeBlock = Math.max(largestFreeBlock, report.largestFreeBlock());
            reuseHits += report.reuseHits();
            reuseMisses += report.reuseMisses();

            blockEntities += inspection.totalBlockEntities();
            sectionTotals.add(inspection.sectionTotals());
            chunkMix.add(inspection.chunkMix());
            mergeCounts(densePaletteSizes, inspection.densePaletteSizes());
            mergeCounts(denseBitsPerBlock, inspection.denseBitsPerBlock());
            denseComparison.add(inspection.denseComparison());

            final EncoderInputDiagnostics uniform = inspection.uniformDiagnostics();
            uniformCandidateSections += uniform.uniformCandidateSections();
            fullUniformCandidateSections += uniform.fullUniformCandidateSections();
            implicitAirRejectedUniformSections += uniform.implicitAirRejectedUniformSections();
            sanityRejectedUniformSections += uniform.sanityRejectedUniformSections();
        }

        private StorageReport toReport() {
            final int totalChunkSections = storedChunks
                    * (CisConstants.MAX_SECTION_Y - CisConstants.MIN_SECTION_Y + 1);
            final int emptySections = Math.max(0, totalChunkSections - sectionTotals.totalSections());

            return new StorageReport(
                    chunkisDir,
                    vanillaDir,
                    chunkisBytes,
                    liveBytes,
                    slackBytes,
                    vanillaBytes,
                    storedChunks,
                    baseChunks,
                    largestRegions.snapshotDescending(),
                    largestChunks.snapshotDescending(),
                    blockEntities,
                    emptySections,
                    sectionTotals.uniformSections(),
                    sectionTotals.defaultSparseSections(),
                    sectionTotals.sparseSections(),
                    sectionTotals.denseSections(),
                    freeBlocks,
                    reusableBytes,
                    largestFreeBlock,
                    reuseHits,
                    reuseMisses,
                    sectionTotals.uniformBits(),
                    sectionTotals.defaultSparseBits(),
                    sectionTotals.sparseBits(),
                    sectionTotals.denseBits(),
                    chunkMix.denseOnlyChunks(),
                    chunkMix.sparseOnlyChunks(),
                    chunkMix.uniformOnlyChunks(),
                    chunkMix.mixedChunks(),
                    Map.copyOf(densePaletteSizes),
                    Map.copyOf(denseBitsPerBlock),
                    largestDenseSections.snapshotDescending(),
                    denseComparison.denseBeatsSparseCount(),
                    denseComparison.denseVsSparseMarginTotal(),
                    denseComparison.denseVsSparseWorstMargin(),
                    denseComparison.denseBeatsDefaultSparseCount(),
                    denseComparison.denseVsDefaultSparseMarginTotal(),
                    denseComparison.denseVsDefaultSparseWorstMargin(),
                    uniformCandidateSections,
                    fullUniformCandidateSections,
                    implicitAirRejectedUniformSections,
                    sanityRejectedUniformSections
            );
        }
    }

    /**
     * Mutable region-level aggregation for one raw CIS file.
     */
    private static final class RegionScanAccumulator {
        private final String regionName;
        private final long fileBytes;
        private final CisRegionInspector.RegionSpaceUsage regionSpace;
        private final BoundedTopList<ChunkReport> largestChunks =
                new BoundedTopList<>(TOP_CHUNKS, CHUNK_BY_LIVE_BYTES);
        private final BoundedTopList<DenseSectionReport> largestDenseSections =
                new BoundedTopList<>(TOP_DENSE_SECTIONS, DENSE_SECTION_BY_ENCODED_BYTES);
        private final SectionEncodingTotals sectionTotals = new SectionEncodingTotals();
        private final ChunkMixCounters chunkMix = new ChunkMixCounters();
        private final DenseComparisonAccumulator denseComparison = new DenseComparisonAccumulator();
        private final Map<Integer, Integer> densePaletteSizes = new TreeMap<>();
        private final Map<Integer, Integer> denseBitsPerBlock = new TreeMap<>();

        private long liveBytes;
        private int storedChunks;
        private int baseChunks;
        private int totalBlockEntities;
        private int uniformCandidateSections;
        private int fullUniformCandidateSections;
        private int implicitAirRejectedUniformSections;
        private int sanityRejectedUniformSections;

        private RegionScanAccumulator(
                final String regionName,
                final long fileBytes,
                final CisRegionInspector.RegionSpaceUsage regionSpace
        ) {
            this.regionName = regionName;
            this.fileBytes = fileBytes;
            this.regionSpace = regionSpace;
        }

        private void addChunk(
                final CisChunkPos pos,
                final int liveBytes,
                final ChunkPayloadDiagnostics payload,
                final DenseSectionAnalysis denseAnalysis,
                final EncoderInputDiagnostics uniformDiagnostics,
                final boolean hasBaseNbt
        ) {
            storedChunks++;
            this.liveBytes += liveBytes;
            if (hasBaseNbt) {
                baseChunks++;
            }

            largestChunks.add(new ChunkReport(
                    pos,
                    liveBytes,
                    payload.totalSections(),
                    payload.uniformSections(),
                    payload.defaultSparseSections(),
                    payload.sparseSections(),
                    payload.denseSections(),
                    payload.blockEntities(),
                    payload.chunkEncodingKind()
            ));

            totalBlockEntities += payload.blockEntities();
            sectionTotals.addPayload(payload);
            chunkMix.add(payload.chunkEncodingKind());

            mergeCounts(densePaletteSizes, denseAnalysis.paletteSizeDistribution());
            mergeCounts(denseBitsPerBlock, denseAnalysis.bitsPerBlockDistribution());
            largestDenseSections.addAll(denseAnalysis.sections());
            denseComparison.add(denseAnalysis);

            uniformCandidateSections += uniformDiagnostics.uniformCandidateSections();
            fullUniformCandidateSections += uniformDiagnostics.fullUniformCandidateSections();
            implicitAirRejectedUniformSections += uniformDiagnostics.implicitAirRejectedUniformSections();
            sanityRejectedUniformSections += uniformDiagnostics.sanityRejectedUniformSections();
        }

        private RegionInspection toInspection() {
            final long slackBytes = regionSpace == null
                    ? Math.max(0L, fileBytes - (HEADER_BYTES + liveBytes))
                    : regionSpace.slackBytes();

            final RegionReport regionReport = new RegionReport(
                    regionName,
                    fileBytes,
                    liveBytes,
                    slackBytes,
                    regionSpace == null ? 0L : regionSpace.reusableBytes(),
                    regionSpace == null ? 0 : regionSpace.freeBlockCount(),
                    regionSpace == null ? 0 : regionSpace.largestFreeBlock(),
                    regionSpace == null ? 0L : regionSpace.reuseHits(),
                    regionSpace == null ? 0L : regionSpace.reuseMisses(),
                    storedChunks,
                    baseChunks
            );

            return new RegionInspection(
                    regionReport,
                    largestChunks.snapshotDescending(),
                    totalBlockEntities,
                    sectionTotals.copy(),
                    chunkMix.copy(),
                    Map.copyOf(densePaletteSizes),
                    Map.copyOf(denseBitsPerBlock),
                    largestDenseSections.snapshotDescending(),
                    denseComparison.copy(),
                    new EncoderInputDiagnostics(
                            uniformCandidateSections,
                            fullUniformCandidateSections,
                            implicitAirRejectedUniformSections,
                            sanityRejectedUniformSections
                    )
            );
        }
    }

    /**
     * Keeps only the largest N values according to an ascending comparator.
     */
    private static final class BoundedTopList<T> {
        private final int capacity;
        private final Comparator<T> ascendingComparator;
        private final PriorityQueue<T> heap;

        private BoundedTopList(final int capacity, final Comparator<T> ascendingComparator) {
            this.capacity = Math.max(0, capacity);
            this.ascendingComparator = ascendingComparator;
            this.heap = new PriorityQueue<>(Math.max(1, this.capacity), ascendingComparator);
        }

        private void add(final T value) {
            if (capacity == 0) {
                return;
            }

            if (heap.size() < capacity) {
                heap.add(value);
                return;
            }

            final T smallestKept = heap.peek();
            if (smallestKept != null && ascendingComparator.compare(value, smallestKept) > 0) {
                heap.poll();
                heap.add(value);
            }
        }

        private void addAll(final List<T> values) {
            for (final T value : values) {
                add(value);
            }
        }

        private List<T> snapshotDescending() {
            final List<T> values = new ArrayList<>(heap);
            values.sort(ascendingComparator.reversed());
            return List.copyOf(values);
        }
    }

    /**
     * Section counts and encoded bit totals, shared by chunk, region, and world aggregation.
     */
    private static final class SectionEncodingTotals {
        private int uniformSections;
        private int defaultSparseSections;
        private int sparseSections;
        private int denseSections;
        private long uniformBits;
        private long defaultSparseBits;
        private long sparseBits;
        private long denseBits;

        private void addPayload(final ChunkPayloadDiagnostics payload) {
            uniformSections += payload.uniformSections();
            defaultSparseSections += payload.defaultSparseSections();
            sparseSections += payload.sparseSections();
            denseSections += payload.denseSections();
            uniformBits += payload.uniformSectionBits();
            defaultSparseBits += payload.defaultSparseSectionBits();
            sparseBits += payload.sparseSectionBits();
            denseBits += payload.denseSectionBits();
        }

        private void add(final SectionEncodingTotals other) {
            uniformSections += other.uniformSections;
            defaultSparseSections += other.defaultSparseSections;
            sparseSections += other.sparseSections;
            denseSections += other.denseSections;
            uniformBits += other.uniformBits;
            defaultSparseBits += other.defaultSparseBits;
            sparseBits += other.sparseBits;
            denseBits += other.denseBits;
        }

        private SectionEncodingTotals copy() {
            final SectionEncodingTotals copy = new SectionEncodingTotals();
            copy.add(this);
            return copy;
        }

        private int totalSections() {
            return uniformSections + defaultSparseSections + sparseSections + denseSections;
        }

        private int uniformSections() {
            return uniformSections;
        }

        private int defaultSparseSections() {
            return defaultSparseSections;
        }

        private int sparseSections() {
            return sparseSections;
        }

        private int denseSections() {
            return denseSections;
        }

        private long uniformBits() {
            return uniformBits;
        }

        private long defaultSparseBits() {
            return defaultSparseBits;
        }

        private long sparseBits() {
            return sparseBits;
        }

        private long denseBits() {
            return denseBits;
        }
    }

    /**
     * High-level chunk mix counters.
     */
    private static final class ChunkMixCounters {
        private int denseOnlyChunks;
        private int sparseOnlyChunks;
        private int uniformOnlyChunks;
        private int mixedChunks;

        private void add(final ChunkEncodingKind kind) {
            switch (kind) {
                case DENSE_ONLY -> denseOnlyChunks++;
                case DEFAULT_SPARSE_ONLY, SPARSE_ONLY -> sparseOnlyChunks++;
                case UNIFORM_ONLY -> uniformOnlyChunks++;
                case MIXED -> mixedChunks++;
                case EMPTY -> {
                    // Empty chunks are represented by stored chunk counts but are not part of the mix breakdown.
                }
            }
        }

        private void add(final ChunkMixCounters other) {
            denseOnlyChunks += other.denseOnlyChunks;
            sparseOnlyChunks += other.sparseOnlyChunks;
            uniformOnlyChunks += other.uniformOnlyChunks;
            mixedChunks += other.mixedChunks;
        }

        private ChunkMixCounters copy() {
            final ChunkMixCounters copy = new ChunkMixCounters();
            copy.add(this);
            return copy;
        }

        private int denseOnlyChunks() {
            return denseOnlyChunks;
        }

        private int sparseOnlyChunks() {
            return sparseOnlyChunks;
        }

        private int uniformOnlyChunks() {
            return uniformOnlyChunks;
        }

        private int mixedChunks() {
            return mixedChunks;
        }
    }

    /**
     * Aggregates dense-vs-sparse byte margin diagnostics.
     */
    private static final class DenseComparisonAccumulator {
        private int samples;
        private int denseBeatsSparseCount;
        private long denseVsSparseMarginTotal;
        private long denseVsSparseWorstMargin = Long.MAX_VALUE;
        private int denseBeatsDefaultSparseCount;
        private long denseVsDefaultSparseMarginTotal;
        private long denseVsDefaultSparseWorstMargin = Long.MAX_VALUE;

        private void record(
                final long sparseMargin,
                final long defaultSparseMargin,
                final boolean hasDefaultSparseEncoding
        ) {
            samples++;
            if (sparseMargin > 0L) {
                denseBeatsSparseCount++;
            }
            if (hasDefaultSparseEncoding && defaultSparseMargin > 0L) {
                denseBeatsDefaultSparseCount++;
            }

            denseVsSparseMarginTotal += sparseMargin;
            denseVsDefaultSparseMarginTotal += defaultSparseMargin;
            denseVsSparseWorstMargin = Math.min(denseVsSparseWorstMargin, sparseMargin);
            denseVsDefaultSparseWorstMargin = Math.min(denseVsDefaultSparseWorstMargin, defaultSparseMargin);
        }

        private void add(final DenseSectionAnalysis analysis) {
            samples += analysis.analyzedSections();
            denseBeatsSparseCount += analysis.denseBeatsSparseCount();
            denseVsSparseMarginTotal += analysis.denseVsSparseMarginTotal();
            denseBeatsDefaultSparseCount += analysis.denseBeatsDefaultSparseCount();
            denseVsDefaultSparseMarginTotal += analysis.denseVsDefaultSparseMarginTotal();

            if (analysis.analyzedSections() > 0) {
                denseVsSparseWorstMargin = Math.min(denseVsSparseWorstMargin, analysis.denseVsSparseWorstMargin());
                denseVsDefaultSparseWorstMargin = Math.min(
                        denseVsDefaultSparseWorstMargin,
                        analysis.denseVsDefaultSparseWorstMargin()
                );
            }
        }

        private void add(final DenseComparisonAccumulator other) {
            samples += other.samples;
            denseBeatsSparseCount += other.denseBeatsSparseCount;
            denseVsSparseMarginTotal += other.denseVsSparseMarginTotal;
            denseBeatsDefaultSparseCount += other.denseBeatsDefaultSparseCount;
            denseVsDefaultSparseMarginTotal += other.denseVsDefaultSparseMarginTotal;

            if (other.samples > 0) {
                denseVsSparseWorstMargin = Math.min(denseVsSparseWorstMargin, other.denseVsSparseWorstMargin);
                denseVsDefaultSparseWorstMargin = Math.min(
                        denseVsDefaultSparseWorstMargin,
                        other.denseVsDefaultSparseWorstMargin
                );
            }
        }

        private DenseComparisonAccumulator copy() {
            final DenseComparisonAccumulator copy = new DenseComparisonAccumulator();
            copy.add(this);
            return copy;
        }

        private int samples() {
            return samples;
        }

        private int denseBeatsSparseCount() {
            return denseBeatsSparseCount;
        }

        private long denseVsSparseMarginTotal() {
            return denseVsSparseMarginTotal;
        }

        private long denseVsSparseWorstMargin() {
            return samples == 0 ? 0L : denseVsSparseWorstMargin;
        }

        private int denseBeatsDefaultSparseCount() {
            return denseBeatsDefaultSparseCount;
        }

        private long denseVsDefaultSparseMarginTotal() {
            return denseVsDefaultSparseMarginTotal;
        }

        private long denseVsDefaultSparseWorstMargin() {
            return samples == 0 ? 0L : denseVsDefaultSparseWorstMargin;
        }
    }

    /**
     * Small cursor wrapper that turns ByteBuffer underflow/invalid offsets into IOException
     * messages tied to a field name.
     */
    private record PayloadCursor(ByteBuffer buffer) {
        private PayloadCursor(final byte[] buffer) {
            this(ByteBuffer.wrap(buffer));
        }

        private int readInt(final String field) throws IOException {
            require(Integer.BYTES, field);
            return buffer.getInt();
        }

        private int readNonNegativeInt(final String field) throws IOException {
            final int value = readInt(field);
            if (value < 0) {
                throw new IOException("Negative " + field + ": " + value);
            }
            return value;
        }

        private int readUnsignedShort(final String field) throws IOException {
            require(Short.BYTES, field);
            return buffer.getShort() & 0xFFFF;
        }

        private byte[] readBytes(final int length, final String field) throws IOException {
            require(length, field);
            final byte[] bytes = new byte[length];
            buffer.get(bytes);
            return bytes;
        }

        private void skip(final int bytes, final String field) throws IOException {
            require(bytes, field);
            buffer.position(buffer.position() + bytes);
        }

        private void require(final int bytes, final String field) throws IOException {
            if (bytes < 0) {
                throw new IOException("Negative byte count while reading " + field + ": " + bytes);
            }
            if (buffer.remaining() < bytes) {
                throw new IOException("Malformed CIS payload: expected " + bytes
                        + " bytes for " + field
                        + " but only " + buffer.remaining()
                        + " bytes remain");
            }
        }
    }

    /**
     * Reads and validates raw CIS region-file payload ranges.
     *
     * <p>This isolates low-level region-file IO from the higher-level storage
     * diagnostics so {@link #inspectRegion} can stay focused on aggregation.</p>
     */
    private static final class RegionFileReader {
        private RegionFileReader() {
        }

        /**
         * Parses region coordinates from a Chunkis region filename.
         */
        private static RegionCoordinates parseCoordinates(final Path regionPath) throws IOException {
            final Matcher matcher = REGION_FILE_PATTERN.matcher(regionPath.getFileName().toString());
            if (!matcher.matches()) {
                throw new IOException("Unexpected region filename: " + regionPath.getFileName());
            }

            try {
                return new RegionCoordinates(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            } catch (final NumberFormatException e) {
                throw new IOException("Region coordinates out of integer range: " + regionPath.getFileName(), e);
            }
        }

        /**
         * Rejects header entries whose payload range points outside the file body.
         */
        private static void validateChunkRange(
                final Path regionPath,
                final int offset,
                final int length,
                final long fileBytes
        ) throws IOException {
            final long end = (long) offset + length;
            if (offset < HEADER_BYTES || end > fileBytes) {
                throw new IOException("Invalid chunk range in " + regionPath.getFileName()
                        + ": offset=" + offset
                        + ", length=" + length
                        + ", fileBytes=" + fileBytes);
            }
        }

        /**
         * Reads one compressed chunk payload directly from the raw region file.
         */
        private static byte[] readChunkBytes(
                final FileChannel channel,
                final int offset,
                final int length
        ) throws IOException {
            final ByteBuffer buffer = ByteBuffer.allocate(length);
            readFully(channel, buffer, offset);
            return buffer.array();
        }

        /**
         * Decompresses the Zstd-compressed chunk payload stored in the region file.
         */
        private static byte[] decompressChunkPayload(final byte[] compressed) throws IOException {
            final long decompressedSize = Zstd.decompressedSize(compressed);
            if (Zstd.isError(decompressedSize)) {
                throw new IOException("Failed to read CIS Zstd size: " + Zstd.getErrorName(decompressedSize));
            }
            if (decompressedSize <= 0L || decompressedSize > Integer.MAX_VALUE) {
                throw new IOException("Invalid CIS Zstd payload size: " + decompressedSize);
            }
            return Zstd.decompress(compressed, (int) decompressedSize);
        }
    }

    /**
     * Mutable parser result for one chunk's section bitstream.
     */
    private static final class ChunkSectionPayloadAccumulator {
        private final int globalBits;
        private final List<SectionPayloadDiagnostics> sectionReports;
        private int uniformSections;
        private int defaultSparseSections;
        private int sparseSections;
        private int denseSections;
        private long uniformBits;
        private long defaultSparseBits;
        private long sparseBits;
        private long denseBits;
        private boolean sawUniform;
        private boolean sawDefaultSparse;
        private boolean sawSparse;
        private boolean sawDense;

        private ChunkSectionPayloadAccumulator(final int sectionCount, final int globalBits) {
            this.globalBits = globalBits;
            this.sectionReports = new ArrayList<>(sectionCount);
        }

        private void addUniform(final int sectionY, final long encodedBits) {
            uniformSections++;
            uniformBits += encodedBits;
            sawUniform = true;
            sectionReports.add(new SectionPayloadDiagnostics(
                    sectionY,
                    SectionEncodingKind.UNIFORM,
                    encodedBits,
                    0,
                    0
            ));
        }

        private void addDefaultSparse(final int sectionY, final int exceptionCount) {
            final long encodedBits = 1L + CisConstants.BLOCK_COUNT_BITS + globalBits
                    + CisConstants.BLOCK_COUNT_BITS
                    + ((long) exceptionCount * (SPARSE_ENTRY_POSITION_BITS + globalBits));
            defaultSparseSections++;
            defaultSparseBits += encodedBits;
            sawDefaultSparse = true;
            sectionReports.add(new SectionPayloadDiagnostics(
                    sectionY,
                    SectionEncodingKind.DEFAULT_SPARSE,
                    encodedBits,
                    0,
                    0
            ));
        }

        private void addSparse(final int sectionY, final int blockCount) {
            final long encodedBits = 1L + CisConstants.BLOCK_COUNT_BITS
                    + ((long) blockCount * (SPARSE_ENTRY_POSITION_BITS + globalBits));
            sparseSections++;
            sparseBits += encodedBits;
            sawSparse = true;
            sectionReports.add(new SectionPayloadDiagnostics(
                    sectionY,
                    SectionEncodingKind.SPARSE,
                    encodedBits,
                    0,
                    0
            ));
        }

        private void addDense(
                final int sectionY,
                final int localSize,
                final int bitsPerBlock,
                final int paletteBits
        ) {
            final long encodedBits = 1L + paletteBits
                    + ((long) localSize * globalBits)
                    + ((long) SECTION_BLOCKS * bitsPerBlock);
            denseSections++;
            denseBits += encodedBits;
            sawDense = true;
            sectionReports.add(new SectionPayloadDiagnostics(
                    sectionY,
                    SectionEncodingKind.DENSE,
                    encodedBits,
                    localSize,
                    bitsPerBlock
            ));
        }

        private int uniformSections() {
            return uniformSections;
        }

        private int defaultSparseSections() {
            return defaultSparseSections;
        }

        private int sparseSections() {
            return sparseSections;
        }

        private int denseSections() {
            return denseSections;
        }

        private long uniformBits() {
            return uniformBits;
        }

        private long defaultSparseBits() {
            return defaultSparseBits;
        }

        private long sparseBits() {
            return sparseBits;
        }

        private long denseBits() {
            return denseBits;
        }

        private int globalBits() {
            return globalBits;
        }

        private List<SectionPayloadDiagnostics> sectionReports() {
            return List.copyOf(sectionReports);
        }

        private boolean sawUniform() {
            return sawUniform;
        }

        private boolean sawDefaultSparse() {
            return sawDefaultSparse;
        }

        private boolean sawSparse() {
            return sawSparse;
        }

        private boolean sawDense() {
            return sawDense;
        }
    }

    /**
     * Handles output formatting only. Report construction stays in the scanners/accumulators.
     */
    private static final class ReportRenderer {
        private ReportRenderer() {
        }

        private static void send(
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

            sendLine(source, "[Chunkis] Storage report for " + world.getRegistryKey().getValue());
            sendLine(
                    source, "  Chunkis bytes: " + formatBytes(report.chunkisBytes())
                            + " | live: " + formatBytes(report.liveBytes())
                            + " | slack: " + formatBytes(report.slackBytes())
            );
            sendLine(
                    source, "  Free blocks: " + report.freeBlocks()
                            + " | reusable: " + formatBytes(report.reusableBytes())
                            + " | largest free: " + formatBytes(report.largestFreeBlock())
                            + " | reuse hits/misses: " + report.reuseHits() + "/" + report.reuseMisses()
            );
            sendLine(
                    source, "  Stored chunks: " + report.storedChunks()
                            + " | with base NBT: " + report.baseChunks()
                            + " (" + formatPercent(baseShare) + ")"
                            + " | sparse-only: " + baseFreeChunks
            );
            sendLine(
                    source, "  Avg live / chunk: " + formatBytes(averageBytes(
                            report.liveBytes(),
                            report.storedChunks()
                    ))
                            + " | block entities: " + report.blockEntities()
            );
            sendLine(
                    source, "  Vanilla region bytes: " + formatBytes(report.vanillaBytes())
                            + " | Chunkis live / vanilla: " + formatPercent(liveVsVanilla)
            );
            sendLine(
                    source, "  Sections: empty " + report.emptySections()
                            + " | uniform " + report.uniformSections()
                            + " | default-sparse " + report.defaultSparseSections()
                            + " | sparse " + report.sparseSections()
                            + " | dense " + report.denseSections()
            );
            sendLine(
                    source, "  Avg bytes / section: empty 0 B"
                            + " | uniform " + formatBytes(averageSectionBytes(
                            report.uniformSectionBits(),
                            report.uniformSections()
                    ))
                            + " | default-sparse "
                            + formatBytes(averageSectionBytes(
                            report.defaultSparseSectionBits(),
                            report.defaultSparseSections()
                    ))
                            + " | sparse " + formatBytes(averageSectionBytes(
                            report.sparseSectionBits(),
                            report.sparseSections()
                    ))
                            + " | dense " + formatBytes(averageSectionBytes(
                            report.denseSectionBits(),
                            report.denseSections()
                    ))
            );
            sendLine(
                    source, "  Chunk section mix: dense-only " + report.denseOnlyChunks()
                            + " | sparse-only " + report.sparseOnlyChunks()
                            + " | uniform-only " + report.uniformOnlyChunks()
                            + " | mixed " + report.mixedChunks()
            );
            sendLine(
                    source, "  Uniform candidates: " + report.uniformCandidateSections()
                            + " | full single-state: " + report.fullUniformCandidateSections()
                            + " | rejected implicit-air: " + report.implicitAirRejectedUniformSections()
                            + " | rejected sanity: " + report.sanityRejectedUniformSections()
            );

            sendDenseSummary(source, report);
            sendLine(source, "  Chunkis dir: " + report.chunkisDir());
            sendLine(source, "  Vanilla dir: " + report.vanillaDir());
            sendRegionSummary(source, report, topRegions);
            sendLargestChunks(source, report);
            sendLargestDenseSections(source, report);
        }

        private static void sendDenseSummary(final ServerCommandSource source, final StorageReport report) {
            if (report.denseSections() <= 0) {
                return;
            }

            sendLine(
                    source, "  Dense palette sizes: " + formatDistribution(report.densePaletteSizes())
                            + " | bits/block: " + formatDistribution(report.denseBitsPerBlock())
            );
            sendLine(
                    source, "  Dense vs sparse: beat " + report.denseBeatsSparseCount() + "/" + report.denseSections()
                            + " | avg margin " + formatBytes(safeAverage(
                            report.denseVsSparseMarginTotal(),
                            report.denseSections()
                    ))
                            + " | worst " + formatSignedBytes(report.denseVsSparseWorstMargin())
            );
            sendLine(
                    source, "  Dense vs default-sparse: beat " + report.denseBeatsDefaultSparseCount()
                            + "/" + report.denseSections()
                            + " | avg margin " + formatBytes(safeAverage(
                            report.denseVsDefaultSparseMarginTotal(),
                            report.denseSections()
                    ))
                            + " | worst " + formatSignedBytes(report.denseVsDefaultSparseWorstMargin())
            );
        }

        private static void sendRegionSummary(
                final ServerCommandSource source,
                final StorageReport report,
                final int topRegions
        ) {
            final int limit = Math.min(topRegions, report.regions().size());
            if (limit == 0) {
                sendLine(source, "  No Chunkis region files found.");
                return;
            }

            sendLine(source, "  Top " + limit + " regions by live bytes:");
            for (int i = 0; i < limit; i++) {
                final RegionReport region = report.regions().get(i);
                sendLine(
                        source, "    " + region.name()
                                + " | live " + formatBytes(region.liveBytes())
                                + " | slack " + formatBytes(region.slackBytes())
                                + " (" + formatPercent(slackPercent(region.fileBytes(), region.slackBytes())) + ")"
                                + " | free-list " + region.freeBlockCount()
                                + " | stored " + region.storedChunks()
                                + " | base " + region.baseChunks()
                );
            }
        }

        private static void sendLargestChunks(final ServerCommandSource source, final StorageReport report) {
            final int chunkLimit = Math.min(TOP_CHUNKS, report.chunks().size());
            if (chunkLimit == 0) {
                return;
            }

            sendLine(source, "  Largest " + chunkLimit + " chunks by live bytes:");
            for (int i = 0; i < chunkLimit; i++) {
                final ChunkReport chunk = report.chunks().get(i);
                sendLine(
                        source, "    " + chunk.pos()
                                + " | live " + formatBytes(chunk.liveBytes())
                                + " | sections e/u/ds/s/d "
                                + "0/" + chunk.uniformSections() + "/" + chunk.defaultSparseSections() + "/"
                                + chunk.sparseSections() + "/" + chunk.denseSections()
                                + " | block entities " + chunk.blockEntities()
                                + " | mix " + chunk.chunkEncodingKind().label
                );
            }
        }

        private static void sendLargestDenseSections(final ServerCommandSource source, final StorageReport report) {
            final int denseLimit = Math.min(TOP_DENSE_SECTIONS, report.denseSectionsList().size());
            if (denseLimit == 0) {
                return;
            }

            sendLine(source, "  Top " + denseLimit + " dense sections:");
            for (int i = 0; i < denseLimit; i++) {
                final DenseSectionReport dense = report.denseSectionsList().get(i);
                sendLine(
                        source, "    " + dense.pos() + " @y=" + dense.sectionY()
                                + " | bytes " + formatBytes(dense.encodedBytes())
                                + " | palette " + dense.localPaletteSize()
                                + " | bits/block " + dense.bitsPerBlock()
                                + " | vs sparse " + formatSignedBytes(dense.sparseMarginBytes())
                                + " | vs ds " + formatSignedBytes(dense.defaultSparseMarginBytes())
                                + " | top " + dense.commonStates()
                );
            }
        }

        private static void sendLine(final ServerCommandSource source, final String line) {
            source.sendFeedback(() -> Text.literal(line), false);
        }
    }

    private record RegionCoordinates(int x, int z) {
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
            List<RegionReport> regions,
            List<ChunkReport> chunks,
            int blockEntities,
            int emptySections,
            int uniformSections,
            int defaultSparseSections,
            int sparseSections,
            int denseSections,
            int freeBlocks,
            long reusableBytes,
            int largestFreeBlock,
            long reuseHits,
            long reuseMisses,
            long uniformSectionBits,
            long defaultSparseSectionBits,
            long sparseSectionBits,
            long denseSectionBits,
            int denseOnlyChunks,
            int sparseOnlyChunks,
            int uniformOnlyChunks,
            int mixedChunks,
            Map<Integer, Integer> densePaletteSizes,
            Map<Integer, Integer> denseBitsPerBlock,
            List<DenseSectionReport> denseSectionsList,
            int denseBeatsSparseCount,
            long denseVsSparseMarginTotal,
            long denseVsSparseWorstMargin,
            int denseBeatsDefaultSparseCount,
            long denseVsDefaultSparseMarginTotal,
            long denseVsDefaultSparseWorstMargin,
            int uniformCandidateSections,
            int fullUniformCandidateSections,
            int implicitAirRejectedUniformSections,
            int sanityRejectedUniformSections
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
            long reusableBytes,
            int freeBlockCount,
            int largestFreeBlock,
            long reuseHits,
            long reuseMisses,
            int storedChunks,
            int baseChunks
    ) {
    }

    /**
     * Immutable per-chunk breakdown used for largest-chunk reporting.
     */
    private record ChunkReport(
            CisChunkPos pos,
            long liveBytes,
            int totalSections,
            int uniformSections,
            int defaultSparseSections,
            int sparseSections,
            int denseSections,
            int blockEntities,
            ChunkEncodingKind chunkEncodingKind
    ) {
    }

    /**
     * Immutable bundle of one region summary plus all derived chunk/section
     * metrics collected while scanning that region.
     */
    private record RegionInspection(
            RegionReport regionReport,
            List<ChunkReport> chunkReports,
            int totalBlockEntities,
            SectionEncodingTotals sectionTotals,
            ChunkMixCounters chunkMix,
            Map<Integer, Integer> densePaletteSizes,
            Map<Integer, Integer> denseBitsPerBlock,
            List<DenseSectionReport> denseSectionReports,
            DenseComparisonAccumulator denseComparison,
            EncoderInputDiagnostics uniformDiagnostics
    ) {
    }

    /**
     * Uniform-selection diagnostics derived from the encoder input model.
     */
    record EncoderInputDiagnostics(
            int uniformCandidateSections,
            int fullUniformCandidateSections,
            int implicitAirRejectedUniformSections,
            int sanityRejectedUniformSections
    ) {
    }

    /**
     * Result of checking whether a section contains exactly one explicit state.
     */
    private record SectionUniformDiagnostics(
            int explicitBlockCount,
            boolean singleExplicitState
    ) {
    }

    /**
     * Parsed section-encoding and block-entity diagnostics from one raw CIS
     * chunk payload.
     */
    record ChunkPayloadDiagnostics(
            int totalSections,
            int uniformSections,
            int defaultSparseSections,
            int sparseSections,
            int denseSections,
            int blockEntities,
            long uniformSectionBits,
            long defaultSparseSectionBits,
            long sparseSectionBits,
            long denseSectionBits,
            int globalBits,
            List<SectionPayloadDiagnostics> sections,
            ChunkEncodingKind chunkEncodingKind
    ) {
    }

    /**
     * Operator-facing summary for one dense section in the "largest dense
     * sections" report.
     */
    private record DenseSectionReport(
            CisChunkPos pos,
            int sectionY,
            long encodedBytes,
            int localPaletteSize,
            int bitsPerBlock,
            long sparseMarginBytes,
            long defaultSparseMarginBytes,
            String commonStates
    ) {
    }

    /**
     * Aggregated diagnostics collected from all dense sections in one region.
     */
    private record DenseSectionAnalysis(
            Map<Integer, Integer> paletteSizeDistribution,
            Map<Integer, Integer> bitsPerBlockDistribution,
            List<DenseSectionReport> sections,
            int analyzedSections,
            int denseBeatsSparseCount,
            long denseVsSparseMarginTotal,
            long denseVsSparseWorstMargin,
            int denseBeatsDefaultSparseCount,
            long denseVsDefaultSparseMarginTotal,
            long denseVsDefaultSparseWorstMargin
    ) {
    }

    /**
     * Estimated default-sparse exception count for one logical section.
     */
    private record DefaultSparseReport(
            int exceptionCount
    ) {
    }

    /**
     * Parsed storage-level diagnostics for one encoded section payload.
     */
    record SectionPayloadDiagnostics(
            int sectionY,
            SectionEncodingKind kind,
            long encodedBits,
            int localPaletteSize,
            int bitsPerBlock
    ) {
    }

    /**
     * Encoded section kinds that can appear in a stored chunk payload.
     */
    private enum SectionEncodingKind {
        UNIFORM,
        DEFAULT_SPARSE,
        SPARSE,
        DENSE
    }

    /**
     * High-level chunk encoding mix label used in command output.
     */
    enum ChunkEncodingKind {
        EMPTY("empty"),
        UNIFORM_ONLY("uniform"),
        DEFAULT_SPARSE_ONLY("default-sparse"),
        SPARSE_ONLY("sparse"),
        DENSE_ONLY("dense"),
        MIXED("mixed");

        /**
         * Human-readable label used in command output.
         */
        final String label;

        /**
         * Creates one chunk encoding label.
         */
        ChunkEncodingKind(final String label) {
            this.label = label;
        }
    }
}


