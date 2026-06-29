package io.liparakis.chunkis.command.report;

import io.liparakis.chunkis.core.CisChunkPos;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class StorageReportModels {

    private StorageReportModels() {
        throw new AssertionError("Utility class");
    }

    public enum SectionEncodingKind {
        UNIFORM,
        DEFAULT_SPARSE,
        SPARSE,
        DENSE
    }

    public enum ChunkEncodingKind {
        EMPTY("empty"),
        UNIFORM_ONLY("uniform"),
        DEFAULT_SPARSE_ONLY("default-sparse"),
        SPARSE_ONLY("sparse"),
        DENSE_ONLY("dense"),
        MIXED("mixed");

        public final String label;

        ChunkEncodingKind(final String label) {
            this.label = label;
        }
    }

    public record RegionCoordinates(int x, int z) {

    }

    public record StorageReport(
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

    public record RegionReport(
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

    public record ChunkReport(
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

    public record RegionInspection(
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

    public record EncoderInputDiagnostics(
            int uniformCandidateSections,
            int fullUniformCandidateSections,
            int implicitAirRejectedUniformSections,
            int sanityRejectedUniformSections
    ) {

    }

    public record SectionUniformDiagnostics(
            int explicitBlockCount,
            boolean singleExplicitState
    ) {

    }

    public record ChunkPayloadDiagnostics(
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

    public record DenseSectionReport(
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

    public record DenseSectionAnalysis(
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

    public record DefaultSparseReport(
            int exceptionCount
    ) {

    }

    public record SectionPayloadDiagnostics(
            int sectionY,
            SectionEncodingKind kind,
            long encodedBits,
            int localPaletteSize,
            int bitsPerBlock
    ) {

    }

    public static final class SectionEncodingTotals {

        public int uniformSections;
        public int defaultSparseSections;
        public int sparseSections;
        public int denseSections;
        public long uniformBits;
        public long defaultSparseBits;
        public long sparseBits;
        public long denseBits;

        public void addPayload(final ChunkPayloadDiagnostics payload) {
            uniformSections += payload.uniformSections();
            defaultSparseSections += payload.defaultSparseSections();
            sparseSections += payload.sparseSections();
            denseSections += payload.denseSections();
            uniformBits += payload.uniformSectionBits();
            defaultSparseBits += payload.defaultSparseSectionBits();
            sparseBits += payload.sparseSectionBits();
            denseBits += payload.denseSectionBits();
        }

        public void add(final SectionEncodingTotals other) {
            uniformSections += other.uniformSections;
            defaultSparseSections += other.defaultSparseSections;
            sparseSections += other.sparseSections;
            denseSections += other.denseSections;
            uniformBits += other.uniformBits;
            defaultSparseBits += other.defaultSparseBits;
            sparseBits += other.sparseBits;
            denseBits += other.denseBits;
        }

        public SectionEncodingTotals copy() {
            final SectionEncodingTotals copy = new SectionEncodingTotals();
            copy.add(this);
            return copy;
        }

        public int totalSections() {
            return uniformSections + defaultSparseSections + sparseSections + denseSections;
        }
    }

    public static final class ChunkMixCounters {

        public int denseOnlyChunks;
        public int sparseOnlyChunks;
        public int uniformOnlyChunks;
        public int mixedChunks;

        public void add(final ChunkEncodingKind kind) {
            switch (kind) {
                case DENSE_ONLY -> denseOnlyChunks++;
                case DEFAULT_SPARSE_ONLY, SPARSE_ONLY -> sparseOnlyChunks++;
                case UNIFORM_ONLY -> uniformOnlyChunks++;
                case MIXED -> mixedChunks++;
                case EMPTY -> {
                }
            }
        }

        public void add(final ChunkMixCounters other) {
            denseOnlyChunks += other.denseOnlyChunks;
            sparseOnlyChunks += other.sparseOnlyChunks;
            uniformOnlyChunks += other.uniformOnlyChunks;
            mixedChunks += other.mixedChunks;
        }

        public ChunkMixCounters copy() {
            final ChunkMixCounters copy = new ChunkMixCounters();
            copy.add(this);
            return copy;
        }
    }

    public static final class DenseComparisonAccumulator {

        public int samples;
        public int denseBeatsSparseCount;
        public long denseVsSparseMarginTotal;
        public long denseVsSparseWorstMargin = Long.MAX_VALUE;
        public int denseBeatsDefaultSparseCount;
        public long denseVsDefaultSparseMarginTotal;
        public long denseVsDefaultSparseWorstMargin = Long.MAX_VALUE;

        public void record(
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

        public void add(final DenseSectionAnalysis analysis) {
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

        public void add(final DenseComparisonAccumulator other) {
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

        public DenseComparisonAccumulator copy() {
            final DenseComparisonAccumulator copy = new DenseComparisonAccumulator();
            copy.add(this);
            return copy;
        }
    }
}
