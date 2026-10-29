package io.liparakis.chunkis.command.report;

import io.liparakis.chunkis.command.report.StorageReportModels.ChunkPayloadDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkReport;
import io.liparakis.chunkis.command.report.StorageReportModels.DefaultSparseReport;
import io.liparakis.chunkis.command.report.StorageReportModels.DenseComparisonAccumulator;
import io.liparakis.chunkis.command.report.StorageReportModels.DenseSectionAnalysis;
import io.liparakis.chunkis.command.report.StorageReportModels.DenseSectionReport;
import io.liparakis.chunkis.command.report.StorageReportModels.EncoderInputDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionCoordinates;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionInspection;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionReport;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionEncodingKind;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionPayloadDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionUniformDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.StorageReport;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.model.CisChunk;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.core.model.CisSection;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.io.region.CisRegionInspector;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.WorldSavePath;

/**
 * Analyzer that scans and collects storage metrics for both Vanilla region MCA files and Chunkis CIS files.
 *
 * <p>It compiles information about storage savings, chunk encoding kinds (uniform, sparse, dense, mixed),
 * free space allocations, and block entity density in a Minecraft server world.</p>
 */
public final class StorageMetricsAnalyzer {

    /**
     * Byte size of allocation headers inside CIS region files.
     */
    private static final int HEADER_BYTES = 8192;

    /**
     * Number of chunks per region file (32 x 32 = 1024 slots).
     */
    private static final int HEADER_SLOTS = 1024;

    /**
     * Number of block positions inside a single Minecraft chunk section.
     */
    private static final int SECTION_BLOCKS = 4096;

    /**
     * Number of bits representing individual sparse block positions.
     */
    private static final int SPARSE_ENTRY_POSITION_BITS = 12;

    /**
     * Number of top chunks to include in report detail lists.
     */
    static final int TOP_CHUNKS = 10;

    /**
     * Number of top dense sections to analyze.
     */
    static final int TOP_DENSE_SECTIONS = 20;

    /**
     * Comparator sorting region reports descending/ascending by live bytes size.
     */
    static final Comparator<RegionReport> REGION_BY_LIVE_BYTES =
            Comparator.comparingLong(RegionReport::liveBytes)
                    .thenComparing(RegionReport::name);

    /**
     * Comparator sorting chunk reports by live byte sizes.
     */
    static final Comparator<ChunkReport> CHUNK_BY_LIVE_BYTES =
            Comparator.comparingLong(ChunkReport::liveBytes)
                    .thenComparing(report -> report.pos()
                            .toString());

    /**
     * Comparator sorting dense section reports by encoded byte sizes.
     */
    static final Comparator<DenseSectionReport> DENSE_SECTION_BY_ENCODED_BYTES =
            Comparator.comparingLong(DenseSectionReport::encodedBytes)
                    .thenComparing(report -> report.pos()
                            .toString())
                    .thenComparingInt(DenseSectionReport::sectionY);

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always, as instantiation is not allowed
     */
    private StorageMetricsAnalyzer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Scans the directories of the given ServerWorld to compile a complete StorageReport.
     *
     * <p>Inspects all custom Chunkis region files (.cis) and summarizes vanilla region directory (.mca) file sizes.</p>
     *
     * @param world      the server world to inspect, must not be null
     * @param topRegions the number of top-sized regions to list in the final report details
     * @return a compiled StorageReport populated with sizing and layout metrics
     * @throws IOException if directory or file reads fail
     */
    public static StorageReport inspectWorld(final ServerWorld world, final int topRegions) throws IOException {
        final Path saveRoot = Objects.requireNonNull(world.getServer())
                .getSavePath(WorldSavePath.ROOT);
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
                        regionSpaceByName.get(regionPath.getFileName()
                                .toString())
                ));
            }
        }

        return accumulator.toReport();
    }

    /**
     * Maps region space usages by their filename to allow O(1) lookups during traversal.
     *
     * @param usages collection of space usage indicators
     * @return a map indexed by region filename strings
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
     * Inspects the contents of a specific CIS region file, compiling metrics for all contained chunks.
     *
     * @param storage     the storage provider adapter
     * @param regionPath  file path of the region file
     * @param regionSpace precalculated space metrics, may be null
     * @return region inspection findings
     * @throws IOException if reading the file channel or payload fails
     */
    private static RegionInspection inspectRegion(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final Path regionPath,
            final CisRegionInspector.RegionSpaceUsage regionSpace
    ) throws IOException {
        final RegionCoordinates coordinates = CisPayloadDiagnosticsReader.RegionFileReader.parseCoordinates(regionPath);
        final long physicalBytes = regionSpace == null ? Files.size(regionPath) : regionSpace.physicalBytes();
        final RegionScanAccumulator accumulator = new RegionScanAccumulator(
                regionPath.getFileName()
                        .toString(),
                physicalBytes,
                regionSpace
        );

        try (FileChannel channel = FileChannel.open(regionPath, StandardOpenOption.READ)) {
            final long channelBytes = channel.size();
            final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            CisPayloadDiagnosticsReader.readFully(channel, header, 0L);
            header.flip();

            for (int slot = 0; slot < HEADER_SLOTS; slot++) {
                final int offset = header.getInt();
                final int length = header.getInt();

                if (offset <= 0 || length <= 0) {
                    continue;
                }

                CisPayloadDiagnosticsReader.RegionFileReader.validateChunkRange(regionPath, offset, length,
                        channelBytes);
                final var pos = FabricCisStorageHelper.toStoragePos(
                        (coordinates.x() << 5) + (slot & 31),
                        (coordinates.z() << 5) + (slot >>> 5)
                );

                final byte[] compressed = CisPayloadDiagnosticsReader.RegionFileReader.readChunkBytes(channel, offset
                        , length);
                final byte[] raw = CisPayloadDiagnosticsReader.RegionFileReader.decompressChunkPayload(compressed);
                final ChunkPayloadDiagnostics payload = CisPayloadDiagnosticsReader.inspectChunkPayload(raw);

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
     * Gathers diagnostic metrics about section uniform candidates prior to encoding.
     *
     * @param chunk the chunk to evaluate
     * @return uniform-state diagnostic details
     */
    private static EncoderInputDiagnostics inspectEncoderInput(final CisChunk<BlockState> chunk) {
        int uniformCandidateSections = 0;
        int fullUniformCandidateSections = 0;
        int implicitAirRejectedUniformSections = 0;
        int sanityRejectedUniformSections = 0;

        for (int i = 0; i < chunk.getSectionCount(); i++) {
            final int sectionY = chunk.getSortedSectionIndex(i);
            final CisSection<BlockState> section = chunk.getSections()
                    .get(sectionY);
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
                    sanityRejectedUniformSections++;
                }
            } else {
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
     * Converts a ChunkDelta into a mock encoder chunk representation to perform serialization evaluations.
     *
     * @param delta the chunk delta to parse
     * @return a temporary CisChunk representation
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
     * Inspects dense sections to estimate space efficiency savings compared to sparse formatting.
     *
     * @param pos     chunk coordinates
     * @param payload the chunk's diagnostics payload
     * @param chunk   the mock encoder chunk
     * @return dense section analysis details
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

            final CisSection<BlockState> section = chunk.getSections()
                    .get(sectionPayload.sectionY());
            if (section == null) {
                continue;
            }

            incrementCount(paletteSizes, sectionPayload.localPaletteSize());
            incrementCount(bitsPerBlock, sectionPayload.bitsPerBlock());

            final int sparseEntries = sparseEntryCount(section);
            final long denseBytes = (sectionPayload.encodedBits() + 7L) / 8L;
            final long sparseBytes = (1L + CisConstants.BLOCK_COUNT_BITS
                    + ((long) sparseEntries * (SPARSE_ENTRY_POSITION_BITS + payload.globalBits())) + 7L) / 8L;

            final DefaultSparseReport defaultSparse = defaultSparseReport(section);
            final long defaultSparseBytes = defaultSparse == null
                    ? 0L
                    : (1L + CisConstants.BLOCK_COUNT_BITS + payload.globalBits()
                       + CisConstants.BLOCK_COUNT_BITS
                       + ((long) defaultSparse.exceptionCount()
                          * (SPARSE_ENTRY_POSITION_BITS + payload.globalBits())) + 7L) / 8L;

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
                comparison.samples,
                comparison.denseBeatsSparseCount,
                comparison.denseVsSparseMarginTotal,
                comparison.denseVsSparseWorstMargin,
                comparison.denseBeatsDefaultSparseCount,
                comparison.denseVsDefaultSparseMarginTotal,
                comparison.denseVsDefaultSparseWorstMargin
        );
    }

    /**
     * Determines if a section contains a single uniform block state.
     *
     * @param section the section to examine
     * @return uniform-state diagnostic details
     */
    private static SectionUniformDiagnostics inspectSectionUniform(final CisSection<BlockState> section) {
        if (section.mode == CisSection.MODE_SPARSE) {
            return inspectSparseUniform(section);
        }

        return inspectDenseUniform(section);
    }

    /**
     * Evaluates a sparse-mode section for uniform block state consistency.
     *
     * @param section the sparse section
     * @return uniform-state diagnostics
     */
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

    /**
     * Evaluates a dense-mode section for uniform block state consistency.
     *
     * @param section the dense section
     * @return uniform-state diagnostics
     */
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
     * Computes the number of sparse entries needed to represent the section blocks.
     *
     * @param section the target section
     * @return sparse entry count
     */
    static int sparseEntryCount(final CisSection<BlockState> section) {
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
     * Creates a simulated default-sparse report to calculate its comparative bit size.
     *
     * @param section target section to evaluate
     * @return default-sparse report summary, or null if no exceptions exist
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
     * Returns a string describing the most frequent block states in the section.
     *
     * @param section target section
     * @param limit   maximum states count to list
     * @return description of top logical states
     */
    @SuppressWarnings("SameParameterValue")
    static String topLogicalStates(final CisSection<BlockState> section, final int limit) {
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

        return counts.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("n/a");
    }

    /**
     * Merges a source count map into a target count map.
     *
     * @param target map receiving the counts
     * @param source map containing counts to add
     */
    static void mergeCounts(final Map<Integer, Integer> target, final Map<Integer, Integer> source) {
        for (final Map.Entry<Integer, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    /**
     * Increments the count map key value by 1.
     *
     * @param counts map to update
     * @param key    the key to increment
     */
    static void incrementCount(final Map<Integer, Integer> counts, final int key) {
        counts.merge(key, 1, Integer::sum);
    }

    /**
     * Increments the count map string key value by 1.
     *
     * @param counts map to update
     * @param key    the string key to increment
     */
    static void incrementStringCount(final Map<String, Integer> counts, final String key) {
        counts.merge(key, 1, Integer::sum);
    }

    /**
     * Computes the combined size of all MCA files inside a directory.
     *
     * @param dir the directory path to scan
     * @return combined byte size of all files matching *.mca
     * @throws IOException if directory scanning fails
     */
    static long sumFiles(final Path dir) throws IOException {
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

}
