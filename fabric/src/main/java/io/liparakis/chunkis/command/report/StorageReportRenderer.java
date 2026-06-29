package io.liparakis.chunkis.command.report;

import io.liparakis.chunkis.command.report.StorageReportModels.ChunkReport;
import io.liparakis.chunkis.command.report.StorageReportModels.DenseSectionReport;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionReport;
import io.liparakis.chunkis.command.report.StorageReportModels.StorageReport;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/**
 * Utility for formatting and printing storage report metrics to a Minecraft server command source.
 *
 * <p>Formally displays average section sizes, compression margins, region stats,
 * and chunk state distributions directly into command feedbacks or logs.</p>
 */
public final class StorageReportRenderer {

    /**
     * Value of 1 KiB (Kibibyte) in bytes.
     */
    private static final long ONE_KIB = 1024L;

    /**
     * Value of 1 MiB (Mebibyte) in bytes.
     */
    private static final long ONE_MIB = ONE_KIB * 1024L;

    /**
     * Number of top chunks printed in details list.
     */
    private static final int TOP_CHUNKS = 10;

    /**
     * Number of top dense sections printed in details list.
     */
    private static final int TOP_DENSE_SECTIONS = 20;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always, as instantiation is not allowed
     */
    private StorageReportRenderer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Formats and prints a StorageReport to a ServerCommandSource feedback stream.
     *
     * @param source     the feedback receiver source, must not be null
     * @param world      the target server world, must not be null
     * @param report     the storage report containing metrics to print, must not be null
     * @param topRegions the number of top-sized regions to list
     */
    public static void sendReport(
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

        sendLine(source,
                "[Chunkis] Storage report for " + world.getRegistryKey()
                        .getValue());
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

    /**
     * Prints dense sections statistics summaries.
     *
     * @param source feedback source receiver
     * @param report source report containing metrics
     */
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

    /**
     * Prints summaries of the top regions by live size.
     *
     * @param source     feedback source receiver
     * @param report     source report containing metrics
     * @param topRegions maximum region count to display
     */
    private static void sendRegionSummary(
            final ServerCommandSource source,
            final StorageReport report,
            final int topRegions
    ) {
        final int limit = Math.min(topRegions,
                report.regions()
                        .size());
        if (limit == 0) {
            sendLine(source, "  No Chunkis region files found.");
            return;
        }

        sendLine(source, "  Top " + limit + " regions by live bytes:");
        for (int i = 0; i < limit; i++) {
            final RegionReport region = report.regions()
                    .get(i);
            sendLine(
                    source, "    " + region.name()
                            + " | live " + formatBytes(region.liveBytes())
                            + " | slack " + formatBytes(region.slackBytes())
                            + " (" + formatPercent(
                            slackPercent(region.fileBytes(), region.slackBytes())
                    ) + ")"
                            + " | free-list " + region.freeBlockCount()
                            + " | stored " + region.storedChunks()
                            + " | base " + region.baseChunks()
            );
        }
    }

    /**
     * Prints summaries of the top largest chunks.
     *
     * @param source feedback source receiver
     * @param report source report containing metrics
     */
    private static void sendLargestChunks(final ServerCommandSource source, final StorageReport report) {
        final int chunkLimit = Math.min(TOP_CHUNKS,
                report.chunks()
                        .size());
        if (chunkLimit == 0) {
            return;
        }

        sendLine(source, "  Largest " + chunkLimit + " chunks by live bytes:");
        for (int i = 0; i < chunkLimit; i++) {
            final ChunkReport chunk = report.chunks()
                    .get(i);
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

    /**
     * Prints summaries of the top largest dense sections.
     *
     * @param source feedback source receiver
     * @param report source report containing metrics
     */
    private static void sendLargestDenseSections(final ServerCommandSource source, final StorageReport report) {
        final int denseLimit = Math.min(TOP_DENSE_SECTIONS,
                report.denseSectionsList()
                        .size());
        if (denseLimit == 0) {
            return;
        }

        sendLine(source, "  Top " + denseLimit + " dense sections:");
        for (int i = 0; i < denseLimit; i++) {
            final DenseSectionReport dense = report.denseSectionsList()
                    .get(i);
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

    /**
     * Helper to wrap command feedbacks outputs.
     *
     * @param source feedback source receiver
     * @param line   string line message to print
     */
    private static void sendLine(final ServerCommandSource source, final String line) {
        source.sendFeedback(() -> Text.literal(line), false);
    }

    /**
     * Converts a bit length representation to bytes.
     *
     * @param bits size in bits
     * @return size in bytes rounded up
     */
    private static long bitsToBytes(final long bits) {
        return (bits + 7L) / 8L;
    }

    /**
     * Safely computes division average preventing division by zero.
     *
     * @param total numerator sum
     * @param count denominator quantity
     * @return division average result
     */
    private static long safeAverage(final long total, final int count) {
        if (count <= 0) {
            return 0L;
        }
        return total / count;
    }

    /**
     * Computes average chunk bytes.
     *
     * @param totalBytes total bytes sum
     * @param count      total chunks count
     * @return division average
     */
    private static long averageBytes(final long totalBytes, final int count) {
        if (count <= 0) {
            return 0L;
        }
        return totalBytes / count;
    }

    /**
     * Computes average section bytes from accumulated bits.
     *
     * @param totalBits total bits sum
     * @param count     total sections count
     * @return division average
     */
    private static long averageSectionBytes(final long totalBits, final int count) {
        if (count <= 0) {
            return 0L;
        }
        return bitsToBytes(totalBits) / count;
    }

    /**
     * Formats a counts map as a string listing entry occurrences distribution.
     *
     * @param distribution count map of distribution settings
     * @return formatted string
     */
    private static String formatDistribution(final Map<Integer, Integer> distribution) {
        if (distribution.isEmpty()) {
            return "n/a";
        }

        final StringJoiner joiner = new StringJoiner(", ");
        distribution.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> joiner.add(entry.getKey() + "x" + entry.getValue()));
        return joiner.toString();
    }

    /**
     * Formats raw bytes to KiB/MiB metric labels.
     *
     * @param bytes raw byte size
     * @return formatted size string
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
     * Formats signed byte margins with positive/negative prefix markers.
     *
     * @param bytes raw signed byte count
     * @return formatted margin string
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
     * Formats double percentage ratios.
     *
     * @param value double percentage value
     * @return formatted percentage string
     */
    private static String formatPercent(final double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /**
     * Computes slack ratio percentages.
     *
     * @param physicalBytes total file bytes
     * @param slackBytes    fragmented space bytes
     * @return calculated ratio percentage
     */
    private static double slackPercent(final long physicalBytes, final long slackBytes) {
        if (physicalBytes <= 0L) {
            return 0.0;
        }
        return (100.0 * slackBytes) / physicalBytes;
    }
}
