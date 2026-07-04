package io.liparakis.chunkis.command.report;

import io.liparakis.chunkis.core.CisChunkPos;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Collection of data models, enums, records, and accumulators representing Chunkis storage reports.
 *
 * <p>Used for capturing metrics, file diagnostics, and comparative space evaluations
 * between Vanilla MCA region storage and Chunkis CIS storage format.</p>
 */
public final class StorageReportModels {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always, as instantiation is not allowed
     */
    private StorageReportModels() {
        throw new AssertionError("Utility class");
    }

    /**
     * Types of chunk section encoding formats supported by Chunkis.
     */
    public enum SectionEncodingKind {
        /**
         * Section is completely filled with a single, uniform block state.
         */
        UNIFORM,
        /**
         * Section uses a default background state with localized sparse exceptions.
         */
        DEFAULT_SPARSE,
        /**
         * Section stores sparse non-air state entries.
         */
        SPARSE,
        /**
         * Section uses a local palette mapping and a dense array of bit-packed block states.
         */
        DENSE
    }

    /**
     * Aggregated classification of block state distribution throughout a whole chunk.
     */
    public enum ChunkEncodingKind {
        /**
         * Chunk contains no serialized blocks.
         */
        EMPTY("empty"),
        /**
         * All sections in the chunk are uniform.
         */
        UNIFORM_ONLY("uniform"),
        /**
         * All sections in the chunk use default-sparse encoding.
         */
        DEFAULT_SPARSE_ONLY("default-sparse"),
        /**
         * All sections in the chunk use sparse encoding.
         */
        SPARSE_ONLY("sparse"),
        /**
         * All sections in the chunk use dense encoding.
         */
        DENSE_ONLY("dense"),
        /**
         * Sections in the chunk contain a mixture of different encoding formats.
         */
        MIXED("mixed");

        /**
         * Short human-readable identifier of the chunk encoding kind.
         */
        public final String label;

        /**
         * Enum constructor.
         *
         * @param label the display label
         */
        ChunkEncodingKind(final String label) {
            this.label = label;
        }
    }

    /**
     * Coordinates identifying a region file (32x32 chunks grid).
     *
     * @param x the region X coordinate
     * @param z the region Z coordinate
     */
    public record RegionCoordinates(int x, int z) {

    }

    /**
     * Aggregate report containing storage efficiency statistics for an entire world or world dimension scan.
     *
     * @param chunkisDir                         directory path to custom Chunkis region files
     * @param vanillaDir                         directory path to vanilla region MCA files
     * @param chunkisBytes                       total size of Chunkis storage directory in bytes
     * @param liveBytes                          total size of active chunk payload data inside Chunkis files
     * @param slackBytes                         total size of unallocated/fragmented file space in Chunkis files
     * @param vanillaBytes                       total size of vanilla region storage files in bytes
     * @param storedChunks                       total number of chunks written to Chunkis storage
     * @param baseChunks                         number of chunks containing full Minecraft/Vanilla NBT compounds
     * @param regions                            list of reports for individual scanned region files
     * @param chunks                             list of reports for individual scanned chunks
     * @param blockEntities                      total block entity counts parsed in the world scan
     * @param emptySections                      number of fully empty sections encountered
     * @param uniformSections                    number of uniform-encoded sections
     * @param defaultSparseSections              number of default-sparse sections
     * @param sparseSections                     number of sparse sections
     * @param denseSections                      number of dense sections
     * @param freeBlocks                         total free file blocks recorded in the CIS region headers
     * @param reusableBytes                      total fragmented file space marked reusable for new chunk allocations
     * @param largestFreeBlock                   largest contiguous free block size in bytes
     * @param reuseHits                          number of successful file block reuses during write operations
     * @param reuseMisses                        number of allocation misses prompting file expansion
     * @param uniformSectionBits                 total bits used to store uniform sections
     * @param defaultSparseSectionBits           total bits used to store default-sparse sections
     * @param sparseSectionBits                  total bits used to store sparse sections
     * @param denseSectionBits                   total bits used to store dense sections
     * @param denseOnlyChunks                    total chunks composed entirely of dense sections
     * @param sparseOnlyChunks                   total chunks composed entirely of sparse sections
     * @param uniformOnlyChunks                  total chunks composed entirely of uniform sections
     * @param mixedChunks                        total chunks containing a mix of section encodings
     * @param densePaletteSizes                  distribution map of local palette sizes in dense sections
     * @param denseBitsPerBlock                  distribution map of bits-per-block settings in dense sections
     * @param denseSectionsList                  list of detailed reports for dense sections
     * @param denseBeatsSparseCount              number of dense sections that were smaller than sparse formatting
     * @param denseVsSparseMarginTotal           total bytes saved by dense format over sparse format
     * @param denseVsSparseWorstMargin           worst margin comparison between dense and sparse sections in bytes
     * @param denseBeatsDefaultSparseCount       number of dense sections that were smaller than default-sparse
     *                                           formatting
     * @param denseVsDefaultSparseMarginTotal    total bytes saved by dense format over default-sparse
     * @param denseVsDefaultSparseWorstMargin    worst margin comparison between dense and default-sparse in bytes
     * @param uniformCandidateSections           total candidate sections for uniform encoding optimizations
     * @param fullUniformCandidateSections       total candidates filled with a single explicit non-air block state
     * @param implicitAirRejectedUniformSections sections rejected for uniform due to implicit air blocks
     * @param sanityRejectedUniformSections      sections rejected for uniform due to internal consistency sanity checks
     */
    public record StorageReport(Path chunkisDir, Path vanillaDir, long chunkisBytes, long liveBytes, long slackBytes,
                                long vanillaBytes, int storedChunks, int baseChunks, List<RegionReport> regions,
                                List<ChunkReport> chunks, int blockEntities, int emptySections, int uniformSections,
                                int defaultSparseSections, int sparseSections, int denseSections, int freeBlocks,
                                long reusableBytes, int largestFreeBlock, long reuseHits, long reuseMisses,
                                long uniformSectionBits, long defaultSparseSectionBits, long sparseSectionBits,
                                long denseSectionBits, int denseOnlyChunks, int sparseOnlyChunks, int uniformOnlyChunks,
                                int mixedChunks, Map<Integer, Integer> densePaletteSizes,
                                Map<Integer, Integer> denseBitsPerBlock, List<DenseSectionReport> denseSectionsList,
                                int denseBeatsSparseCount, long denseVsSparseMarginTotal, long denseVsSparseWorstMargin,
                                int denseBeatsDefaultSparseCount, long denseVsDefaultSparseMarginTotal,
                                long denseVsDefaultSparseWorstMargin, int uniformCandidateSections,
                                int fullUniformCandidateSections, int implicitAirRejectedUniformSections,
                                int sanityRejectedUniformSections) {

    }

    /**
     * Statistics for a single region file (.cis) within the world directory.
     *
     * @param name             filename of the region
     * @param fileBytes        total file size on disk in bytes
     * @param liveBytes        size of active chunk payloads in bytes
     * @param slackBytes       unallocated/fragmented file space in bytes
     * @param reusableBytes    fragmented space marked reusable
     * @param freeBlockCount   total free sectors in the region's sector-allocation tables
     * @param largestFreeBlock largest contiguous free sector run in bytes
     * @param reuseHits        successful allocation reuses
     * @param reuseMisses      allocation misses prompting region file grows
     * @param storedChunks     chunks stored in the region
     * @param baseChunks       chunks in the region containing base NBT compounds
     */
    public record RegionReport(String name, long fileBytes, long liveBytes, long slackBytes, long reusableBytes,
                               int freeBlockCount, int largestFreeBlock, long reuseHits, long reuseMisses,
                               int storedChunks, int baseChunks) {

    }

    /**
     * Statistics for a single chunk's physical payload footprint.
     *
     * @param pos                   chunk coordinate pos
     * @param liveBytes             size of the compressed chunk payload in bytes
     * @param totalSections         total sections count
     * @param uniformSections       uniform sections count
     * @param defaultSparseSections default-sparse sections count
     * @param sparseSections        sparse sections count
     * @param denseSections         dense sections count
     * @param blockEntities         number of block entities inside the chunk
     * @param chunkEncodingKind     classification of the chunk's overall section encodings
     */
    public record ChunkReport(CisChunkPos pos, long liveBytes, int totalSections, int uniformSections,
                              int defaultSparseSections, int sparseSections, int denseSections, int blockEntities,
                              ChunkEncodingKind chunkEncodingKind) {

    }

    /**
     * Immediate results generated from scanning a single region file's chunks.
     *
     * @param regionReport        overall region report metrics
     * @param chunkReports        individual reports for chunks in this region
     * @param totalBlockEntities  accumulated block entities count
     * @param sectionTotals       accumulated section totals
     * @param chunkMix            chunk encoding layout statistics
     * @param densePaletteSizes   map tracking palette size occurrences
     * @param denseBitsPerBlock   map tracking bits-per-block occurrences
     * @param denseSectionReports diagnostic metrics for dense sections
     * @param denseComparison     savings comparison metrics
     * @param uniformDiagnostics  uniform candidate verification details
     */
    public record RegionInspection(RegionReport regionReport, List<ChunkReport> chunkReports, int totalBlockEntities,
                                   SectionEncodingTotals sectionTotals, ChunkMixCounters chunkMix,
                                   Map<Integer, Integer> densePaletteSizes, Map<Integer, Integer> denseBitsPerBlock,
                                   List<DenseSectionReport> denseSectionReports,
                                   DenseComparisonAccumulator denseComparison,
                                   EncoderInputDiagnostics uniformDiagnostics) {

    }

    /**
     * Diagnostic counters indicating section structure optimization opportunities for uniform states.
     *
     * @param uniformCandidateSections           candidate sections for uniform optimization
     * @param fullUniformCandidateSections       candidate sections containing exactly one non-air block state
     * @param implicitAirRejectedUniformSections sections rejected due to implicit air blocks
     * @param sanityRejectedUniformSections      sections rejected due to sanity block checks
     */
    public record EncoderInputDiagnostics(int uniformCandidateSections, int fullUniformCandidateSections,
                                          int implicitAirRejectedUniformSections, int sanityRejectedUniformSections) {

    }

    /**
     * Uniform state analysis results for a single chunk section.
     *
     * @param explicitBlockCount  number of explicitly recorded block states
     * @param singleExplicitState whether all recorded states are identical
     */
    public record SectionUniformDiagnostics(int explicitBlockCount, boolean singleExplicitState) {

    }

    /**
     * Decoded size and payload layout metrics for an individual chunk payload.
     *
     * @param totalSections            total sections count
     * @param uniformSections          uniform sections count
     * @param defaultSparseSections    default-sparse sections count
     * @param sparseSections           sparse sections count
     * @param denseSections            dense sections count
     * @param blockEntities            block entities count
     * @param uniformSectionBits       total bits used by uniform sections
     * @param defaultSparseSectionBits total bits used by default-sparse sections
     * @param sparseSectionBits        total bits used by sparse sections
     * @param denseSectionBits         total bits used by dense sections
     * @param globalBits               bits used to index the global registry palette
     * @param sections                 details for each section payload
     * @param chunkEncodingKind        classification of overall chunk encoding kind
     */
    public record ChunkPayloadDiagnostics(int totalSections, int uniformSections, int defaultSparseSections,
                                          int sparseSections, int denseSections, int blockEntities,
                                          long uniformSectionBits, long defaultSparseSectionBits,
                                          long sparseSectionBits, long denseSectionBits, int globalBits,
                                          List<SectionPayloadDiagnostics> sections,
                                          ChunkEncodingKind chunkEncodingKind) {

    }

    /**
     * Diagnostic report highlighting dense-encoding details for a specific section.
     *
     * @param pos                      chunk coordinates
     * @param sectionY                 vertical section Y position
     * @param encodedBytes             section size in bytes
     * @param localPaletteSize         local palette size
     * @param bitsPerBlock             bits per block index configuration
     * @param sparseMarginBytes        savings margin vs sparse format in bytes
     * @param defaultSparseMarginBytes savings margin vs default-sparse format in bytes
     * @param commonStates             top block states description
     */
    public record DenseSectionReport(CisChunkPos pos, int sectionY, long encodedBytes, int localPaletteSize,
                                     int bitsPerBlock, long sparseMarginBytes, long defaultSparseMarginBytes,
                                     String commonStates) {

    }

    /**
     * Consolidated analytical results from inspecting all dense chunk sections.
     *
     * @param paletteSizeDistribution         distribution map of local palette sizes
     * @param bitsPerBlockDistribution        distribution map of bits-per-block settings
     * @param sections                        reports of scanned dense sections
     * @param analyzedSections                total dense sections evaluated
     * @param denseBeatsSparseCount           dense count beating sparse space usage
     * @param denseVsSparseMarginTotal        total bytes saved vs sparse formatting
     * @param denseVsSparseWorstMargin        worst-case comparison vs sparse in bytes
     * @param denseBeatsDefaultSparseCount    dense count beating default-sparse space usage
     * @param denseVsDefaultSparseMarginTotal total bytes saved vs default-sparse formatting
     * @param denseVsDefaultSparseWorstMargin worst-case comparison vs default-sparse in bytes
     */
    public record DenseSectionAnalysis(Map<Integer, Integer> paletteSizeDistribution,
                                       Map<Integer, Integer> bitsPerBlockDistribution,
                                       List<DenseSectionReport> sections, int analyzedSections,
                                       int denseBeatsSparseCount, long denseVsSparseMarginTotal,
                                       long denseVsSparseWorstMargin, int denseBeatsDefaultSparseCount,
                                       long denseVsDefaultSparseMarginTotal, long denseVsDefaultSparseWorstMargin) {

    }

    /**
     * Sizing summary for default-sparse formatting.
     *
     * @param exceptionCount exception blocks count
     */
    public record DefaultSparseReport(int exceptionCount) {

    }

    /**
     * Encoding details extracted from a single section inside a chunk payload.
     *
     * @param sectionY         vertical Y coordinate
     * @param kind             section encoding format kind
     * @param encodedBits      total size in bits
     * @param localPaletteSize local palette entry count
     * @param bitsPerBlock     bits per block index configuration
     */
    public record SectionPayloadDiagnostics(int sectionY, SectionEncodingKind kind, long encodedBits,
                                            int localPaletteSize, int bitsPerBlock) {

    }

    /**
     * Mutable accumulator tracking block section encoding counts and bit lengths.
     */
    public static final class SectionEncodingTotals {

        /**
         * Number of uniform sections.
         */
        public int uniformSections;

        /**
         * Number of default-sparse sections.
         */
        public int defaultSparseSections;

        /**
         * Number of sparse sections.
         */
        public int sparseSections;

        /**
         * Number of dense sections.
         */
        public int denseSections;

        /**
         * Total bits consumed by uniform sections.
         */
        public long uniformBits;

        /**
         * Total bits consumed by default-sparse sections.
         */
        public long defaultSparseBits;

        /**
         * Total bits consumed by sparse sections.
         */
        public long sparseBits;

        /**
         * Total bits consumed by dense sections.
         */
        public long denseBits;

        /**
         * Default constructor.
         */
        public SectionEncodingTotals() {
        }

        /**
         * Adds section metrics from a single chunk payload diagnostics report.
         *
         * @param payload the diagnostics payload to accumulate, must not be null
         */
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

        /**
         * Merges totals from another accumulator.
         *
         * @param other the accumulator to copy metrics from, must not be null
         */
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

        /**
         * Creates a copy of the current totals.
         *
         * @return a new copy of SectionEncodingTotals
         */
        public SectionEncodingTotals copy() {
            final SectionEncodingTotals copy = new SectionEncodingTotals();
            copy.add(this);
            return copy;
        }

        /**
         * Calculates the total number of encoded sections tracked.
         *
         * @return total sections
         */
        public int totalSections() {
            return uniformSections + defaultSparseSections + sparseSections + denseSections;
        }
    }

    /**
     * Accumulator tracking the count of chunks matching specific encoding mixtures.
     */
    public static final class ChunkMixCounters {

        /**
         * Chunks using only dense sections.
         */
        public int denseOnlyChunks;

        /**
         * Chunks using only sparse/default-sparse sections.
         */
        public int sparseOnlyChunks;

        /**
         * Chunks using only uniform sections.
         */
        public int uniformOnlyChunks;

        /**
         * Chunks containing mixed section encodings.
         */
        public int mixedChunks;

        /**
         * Default constructor.
         */
        public ChunkMixCounters() {
        }

        /**
         * Increments the matching counter based on the chunk encoding kind.
         *
         * @param kind the chunk encoding kind
         */
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

        /**
         * Merges the values of another ChunkMixCounters instance into this one.
         *
         * @param other the other counters instance, must not be null
         */
        public void add(final ChunkMixCounters other) {
            denseOnlyChunks += other.denseOnlyChunks;
            sparseOnlyChunks += other.sparseOnlyChunks;
            uniformOnlyChunks += other.uniformOnlyChunks;
            mixedChunks += other.mixedChunks;
        }

        /**
         * Creates a copy of the current mix counts.
         *
         * @return a new ChunkMixCounters instance containing copy of the counts
         */
        public ChunkMixCounters copy() {
            final ChunkMixCounters copy = new ChunkMixCounters();
            copy.add(this);
            return copy;
        }
    }

    /**
     * Accumulates space comparisons between dense section storage and alternative formats (sparse, default-sparse).
     */
    public static final class DenseComparisonAccumulator {

        /**
         * Evaluated sections count.
         */
        public int samples;

        /**
         * Times dense beat sparse formatting.
         */
        public int denseBeatsSparseCount;

        /**
         * Total savings vs sparse.
         */
        public long denseVsSparseMarginTotal;

        /**
         * Worst single case comparison vs sparse.
         */
        public long denseVsSparseWorstMargin = Long.MAX_VALUE;

        /**
         * Times dense beat default-sparse formatting.
         */
        public int denseBeatsDefaultSparseCount;

        /**
         * Total savings vs default-sparse.
         */
        public long denseVsDefaultSparseMarginTotal;

        /**
         * Worst single case comparison vs default-sparse.
         */
        public long denseVsDefaultSparseWorstMargin = Long.MAX_VALUE;

        /**
         * Default constructor.
         */
        public DenseComparisonAccumulator() {
        }

        /**
         * Records space savings/losses from a section analysis.
         *
         * @param sparseMargin             bytes saved by using dense format over sparse
         * @param defaultSparseMargin      bytes saved by using dense format over default-sparse
         * @param hasDefaultSparseEncoding whether default-sparse is a viable comparison format
         */
        public void record(final long sparseMargin,
                final long defaultSparseMargin,
                final boolean hasDefaultSparseEncoding) {
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

        /**
         * Merges a section analysis's aggregate results into this comparison report.
         *
         * @param analysis the section analysis data, must not be null
         */
        public void add(final DenseSectionAnalysis analysis) {
            samples += analysis.analyzedSections();
            denseBeatsSparseCount += analysis.denseBeatsSparseCount();
            denseVsSparseMarginTotal += analysis.denseVsSparseMarginTotal();
            denseBeatsDefaultSparseCount += analysis.denseBeatsDefaultSparseCount();
            denseVsDefaultSparseMarginTotal += analysis.denseVsDefaultSparseMarginTotal();

            if (analysis.analyzedSections() > 0) {
                denseVsSparseWorstMargin = Math.min(denseVsSparseWorstMargin, analysis.denseVsSparseWorstMargin());
                denseVsDefaultSparseWorstMargin = Math.min(denseVsDefaultSparseWorstMargin,
                        analysis.denseVsDefaultSparseWorstMargin());
            }
        }

        /**
         * Merges another accumulator's totals into this one.
         *
         * @param other the other accumulator instance, must not be null
         */
        public void add(final DenseComparisonAccumulator other) {
            samples += other.samples;
            denseBeatsSparseCount += other.denseBeatsSparseCount;
            denseVsSparseMarginTotal += other.denseVsSparseMarginTotal;
            denseBeatsDefaultSparseCount += other.denseBeatsDefaultSparseCount;
            denseVsDefaultSparseMarginTotal += other.denseVsDefaultSparseMarginTotal;

            if (other.samples > 0) {
                denseVsSparseWorstMargin = Math.min(denseVsSparseWorstMargin, other.denseVsSparseWorstMargin);
                denseVsDefaultSparseWorstMargin = Math.min(denseVsDefaultSparseWorstMargin,
                        other.denseVsDefaultSparseWorstMargin);
            }
        }

        /**
         * Returns a copy of the accumulator.
         *
         * @return a new DenseComparisonAccumulator instance
         */
        public DenseComparisonAccumulator copy() {
            final DenseComparisonAccumulator copy = new DenseComparisonAccumulator();
            copy.add(this);
            return copy;
        }
    }
}
