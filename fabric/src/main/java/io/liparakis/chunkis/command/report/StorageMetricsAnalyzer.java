package io.liparakis.chunkis.command.report;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.storage.io.region.CisRegionInspector;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.model.CisChunk;
import io.liparakis.chunkis.storage.model.CisConstants;
import io.liparakis.chunkis.storage.model.CisSection;
import io.liparakis.chunkis.command.report.StorageReportModels.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
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
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;

public final class StorageMetricsAnalyzer {

    private static final int HEADER_BYTES = 8192;
    private static final int HEADER_SLOTS = 1024;
    private static final int SECTION_BLOCKS = 4096;
    private static final int SPARSE_ENTRY_POSITION_BITS = 12;

    private static final int TOP_CHUNKS = 10;
    private static final int TOP_DENSE_SECTIONS = 20;

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

    private StorageMetricsAnalyzer() {
        throw new AssertionError("Utility class");
    }

    public static StorageReport inspectWorld(final ServerWorld world, final int topRegions) throws IOException {
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

    private static Map<String, CisRegionInspector.RegionSpaceUsage> indexRegionSpace(
            final Iterable<CisRegionInspector.RegionSpaceUsage> usages
    ) {
        final Map<String, CisRegionInspector.RegionSpaceUsage> byName = new HashMap<>();
        for (final CisRegionInspector.RegionSpaceUsage usage : usages) {
            byName.put(usage.name(), usage);
        }
        return byName;
    }

    private static RegionInspection inspectRegion(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final Path regionPath,
            final CisRegionInspector.RegionSpaceUsage regionSpace
    ) throws IOException {
        final RegionCoordinates coordinates = CisPayloadDiagnosticsReader.RegionFileReader.parseCoordinates(regionPath);
        final long physicalBytes = regionSpace == null ? Files.size(regionPath) : regionSpace.physicalBytes();
        final RegionScanAccumulator accumulator = new RegionScanAccumulator(
                regionPath.getFileName().toString(),
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
                final CisChunkPos pos = new CisChunkPos(
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

    private static CisChunk<BlockState> buildEncoderChunk(final ChunkDelta<BlockState, NbtCompound> delta) {
        final CisChunk<BlockState> chunk = new CisChunk<>();
        delta.forEachBlock((x, y, z, state) -> {
            if (state != null && !state.isAir()) {
                chunk.addUniqueBlock(x, y, z, state);
            }
        });
        return chunk;
    }

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

    private static void mergeCounts(final Map<Integer, Integer> target, final Map<Integer, Integer> source) {
        for (final Map.Entry<Integer, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static void incrementCount(final Map<Integer, Integer> counts, final int key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static void incrementStringCount(final Map<String, Integer> counts, final String key) {
        counts.merge(key, 1, Integer::sum);
    }

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
                    sectionTotals.uniformSections,
                    sectionTotals.defaultSparseSections,
                    sectionTotals.sparseSections,
                    sectionTotals.denseSections,
                    freeBlocks,
                    reusableBytes,
                    largestFreeBlock,
                    reuseHits,
                    reuseMisses,
                    sectionTotals.uniformBits,
                    sectionTotals.defaultSparseBits,
                    sectionTotals.sparseBits,
                    sectionTotals.denseBits,
                    chunkMix.denseOnlyChunks,
                    chunkMix.sparseOnlyChunks,
                    chunkMix.uniformOnlyChunks,
                    chunkMix.mixedChunks,
                    Map.copyOf(densePaletteSizes),
                    Map.copyOf(denseBitsPerBlock),
                    largestDenseSections.snapshotDescending(),
                    denseComparison.denseBeatsSparseCount,
                    denseComparison.denseVsSparseMarginTotal,
                    denseComparison.denseVsSparseWorstMargin,
                    denseComparison.denseBeatsDefaultSparseCount,
                    denseComparison.denseVsDefaultSparseMarginTotal,
                    denseComparison.denseVsDefaultSparseWorstMargin,
                    uniformCandidateSections,
                    fullUniformCandidateSections,
                    implicitAirRejectedUniformSections,
                    sanityRejectedUniformSections
            );
        }
    }

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
                    ? Math.max(0L, fileBytes - (8192 + liveBytes))
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
}
