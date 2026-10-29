package io.liparakis.chunkis.command.report;

import io.liparakis.chunkis.command.report.StorageReportModels.ChunkMixCounters;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkReport;
import io.liparakis.chunkis.command.report.StorageReportModels.DenseComparisonAccumulator;
import io.liparakis.chunkis.command.report.StorageReportModels.DenseSectionReport;
import io.liparakis.chunkis.command.report.StorageReportModels.EncoderInputDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionInspection;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionReport;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionEncodingTotals;
import io.liparakis.chunkis.command.report.StorageReportModels.StorageReport;
import io.liparakis.chunkis.core.model.CisConstants;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulator compiling world scan statistics during directory traversal.
 */
final class WorldScanAccumulator {

    /**
     * Directory path of Chunkis storage.
     */
    final Path chunkisDir;

    /**
     * Directory path of Vanilla storage.
     */
    final Path vanillaDir;

    /**
     * Size of Vanilla MCA files in bytes.
     */
    final long vanillaBytes;

    /**
     * Bounded list of largest scanned regions.
     */
    final BoundedTopList<RegionReport> largestRegions;

    /**
     * Bounded list of largest scanned chunks.
     */
    final BoundedTopList<ChunkReport> largestChunks =
            new BoundedTopList<>(StorageMetricsAnalyzer.TOP_CHUNKS, StorageMetricsAnalyzer.CHUNK_BY_LIVE_BYTES);

    /**
     * Bounded list of largest dense sections.
     */
    final BoundedTopList<DenseSectionReport> largestDenseSections =
            new BoundedTopList<>(StorageMetricsAnalyzer.TOP_DENSE_SECTIONS, StorageMetricsAnalyzer.DENSE_SECTION_BY_ENCODED_BYTES);

    /**
     * Aggregated encoding totals.
     */
    final SectionEncodingTotals sectionTotals = new SectionEncodingTotals();

    /**
     * Chunks mixture metrics.
     */
    final ChunkMixCounters chunkMix = new ChunkMixCounters();

    /**
     * Dense vs sparse comparison accumulator.
     */
    final DenseComparisonAccumulator denseComparison = new DenseComparisonAccumulator();

    /**
     * Distribution of local dense palette sizes.
     */
    final Map<Integer, Integer> densePaletteSizes = new TreeMap<>();

    /**
     * Distribution of dense bits-per-block configurations.
     */
    final Map<Integer, Integer> denseBitsPerBlock = new TreeMap<>();

    /**
     * Accumulated Chunkis file size in bytes.
     */
    long chunkisBytes;

    /**
     * Accumulated live chunk bytes size.
     */
    long liveBytes;

    /**
     * Accumulated header slack bytes size.
     */
    long slackBytes;

    /**
     * Accumulated reusable fragmented byte size.
     */
    long reusableBytes;

    /**
     * Accumulated allocation hits from free lists.
     */
    long reuseHits;

    /**
     * Accumulated allocation misses requiring file grows.
     */
    long reuseMisses;

    /**
     * Count of stored chunks.
     */
    int storedChunks;

    /**
     * Count of stored base NBT chunks.
     */
    int baseChunks;

    /**
     * Count of free header blocks.
     */
    int freeBlocks;

    /**
     * Largest free block size.
     */
    int largestFreeBlock;

    /**
     * Total block entities count.
     */
    int blockEntities;

    /**
     * Uniform candidate sections count.
     */
    int uniformCandidateSections;

    /**
     * Uniform candidates containing single non-air state.
     */
    int fullUniformCandidateSections;

    /**
     * Candidates rejected due to implicit air blocks.
     */
    int implicitAirRejectedUniformSections;

    /**
     * Candidates rejected due to sanity block checks.
     */
    int sanityRejectedUniformSections;

    /**
     * Constructor.
     *
     * @param chunkisDir   Chunkis path
     * @param vanillaDir   Vanilla path
     * @param vanillaBytes Vanilla bytes size
     * @param topRegions   maximum top regions to report
     */
    WorldScanAccumulator(
            final Path chunkisDir,
            final Path vanillaDir,
            final long vanillaBytes,
            final int topRegions
    ) {
        this.chunkisDir = chunkisDir;
        this.vanillaDir = vanillaDir;
        this.vanillaBytes = vanillaBytes;
        this.largestRegions = new BoundedTopList<>(topRegions, StorageMetricsAnalyzer.REGION_BY_LIVE_BYTES);
    }

    /**
     * Merges the statistics of a region inspection into the world totals.
     *
     * @param inspection the region inspection report to add
     */
    void addRegion(final RegionInspection inspection) {
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
        StorageMetricsAnalyzer.mergeCounts(densePaletteSizes, inspection.densePaletteSizes());
        StorageMetricsAnalyzer.mergeCounts(denseBitsPerBlock, inspection.denseBitsPerBlock());
        denseComparison.add(inspection.denseComparison());

        final EncoderInputDiagnostics uniform = inspection.uniformDiagnostics();
        uniformCandidateSections += uniform.uniformCandidateSections();
        fullUniformCandidateSections += uniform.fullUniformCandidateSections();
        implicitAirRejectedUniformSections += uniform.implicitAirRejectedUniformSections();
        sanityRejectedUniformSections += uniform.sanityRejectedUniformSections();
    }

    /**
     * Compiles the accumulated data into a final StorageReport container.
     *
     * @return StorageReport report summary
     */
    StorageReport toReport() {
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

