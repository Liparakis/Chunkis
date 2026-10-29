package io.liparakis.chunkis.command.report;

import io.liparakis.chunkis.command.report.StorageReportModels.ChunkMixCounters;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkPayloadDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkReport;
import io.liparakis.chunkis.command.report.StorageReportModels.DenseComparisonAccumulator;
import io.liparakis.chunkis.command.report.StorageReportModels.DenseSectionAnalysis;
import io.liparakis.chunkis.command.report.StorageReportModels.DenseSectionReport;
import io.liparakis.chunkis.command.report.StorageReportModels.EncoderInputDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionInspection;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionReport;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionEncodingTotals;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.io.region.CisRegionInspector;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulator compiling region statistics.
 */
final class RegionScanAccumulator {

    /**
     * Name of the region.
     */
    final String regionName;

    /**
     * Byte size of the file on disk.
     */
    final long fileBytes;

    /**
     * Metadata structure of the region space.
     */
    final CisRegionInspector.RegionSpaceUsage regionSpace;

    /**
     * Bounded top list tracking largest chunks in this region.
     */
    final BoundedTopList<ChunkReport> largestChunks =
            new BoundedTopList<>(StorageMetricsAnalyzer.TOP_CHUNKS, StorageMetricsAnalyzer.CHUNK_BY_LIVE_BYTES);

    /**
     * Bounded top list tracking largest dense sections in this region.
     */
    final BoundedTopList<DenseSectionReport> largestDenseSections =
            new BoundedTopList<>(StorageMetricsAnalyzer.TOP_DENSE_SECTIONS, StorageMetricsAnalyzer.DENSE_SECTION_BY_ENCODED_BYTES);

    /**
     * Total section encodings.
     */
    final SectionEncodingTotals sectionTotals = new SectionEncodingTotals();

    /**
     * Chunks mixture metrics.
     */
    final ChunkMixCounters chunkMix = new ChunkMixCounters();

    /**
     * Dense section performance comparison metrics.
     */
    final DenseComparisonAccumulator denseComparison = new DenseComparisonAccumulator();

    /**
     * Local palette sizes distribution map.
     */
    final Map<Integer, Integer> densePaletteSizes = new TreeMap<>();

    /**
     * Local bits-per-block configurations map.
     */
    final Map<Integer, Integer> denseBitsPerBlock = new TreeMap<>();

    /**
     * Accumulated live chunk bytes count.
     */
    long liveBytes;

    /**
     * Stored chunks count.
     */
    int storedChunks;

    /**
     * Stored base NBT chunks count.
     */
    int baseChunks;

    /**
     * Total block entities count.
     */
    int totalBlockEntities;

    /**
     * Uniform candidate sections count.
     */
    int uniformCandidateSections;

    /**
     * Uniform candidate sections filled with single non-air state.
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
     * @param regionName  region filename
     * @param fileBytes   region size on disk
     * @param regionSpace precomputed space metadata, may be null
     */
    RegionScanAccumulator(
            final String regionName,
            final long fileBytes,
            final CisRegionInspector.RegionSpaceUsage regionSpace
    ) {
        this.regionName = regionName;
        this.fileBytes = fileBytes;
        this.regionSpace = regionSpace;
    }

    /**
     * Appends chunk diagnostics statistics to the region accumulator.
     *
     * @param pos                chunk position coordinates
     * @param liveBytes          payload size in bytes
     * @param payload            chunk payload diagnostics report
     * @param denseAnalysis      dense section analysis details
     * @param uniformDiagnostics uniform candidate diagnostics
     * @param hasBaseNbt         whether chunk contains base NBT data
     */
    void addChunk(
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

        StorageMetricsAnalyzer.mergeCounts(densePaletteSizes, denseAnalysis.paletteSizeDistribution());
        StorageMetricsAnalyzer.mergeCounts(denseBitsPerBlock, denseAnalysis.bitsPerBlockDistribution());
        largestDenseSections.addAll(denseAnalysis.sections());
        denseComparison.add(denseAnalysis);

        uniformCandidateSections += uniformDiagnostics.uniformCandidateSections();
        fullUniformCandidateSections += uniformDiagnostics.fullUniformCandidateSections();
        implicitAirRejectedUniformSections += uniformDiagnostics.implicitAirRejectedUniformSections();
        sanityRejectedUniformSections += uniformDiagnostics.sanityRejectedUniformSections();
    }

    /**
     * Compiles the region metrics into a RegionInspection record.
     *
     * @return RegionInspection report container
     */
    RegionInspection toInspection() {
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

