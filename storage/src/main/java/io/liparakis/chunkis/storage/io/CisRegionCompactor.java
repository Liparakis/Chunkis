package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.storage.io.region.CisRegionPaths;
import io.liparakis.chunkis.storage.io.region.RegionFile;
import io.liparakis.chunkis.storage.io.region.RegionKey;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compacts all CIS region files for one storage directory and aggregates the results.
 *
 * <p>This keeps operator/report-oriented maintenance out of {@link CisStorage}
 * while reusing {@link RegionFile}'s compaction implementation.</p>
 */
public final class CisRegionCompactor {

    /**
     * Temporary file suffix used by {@link RegionFile#compactWithReport()}.
     */
    private static final String COMPACT_TEMP_SUFFIX = ".tmp";

    /** Performs cis region compactor. */
    private CisRegionCompactor() {
        throw new AssertionError("Utility class");
    }

    /**
     * Compacts every region known to this storage, including cached open handles and uncached files on disk.
     *
     * @param storage storage whose region directory should be compacted
     * @return aggregate compaction result
     */
    public static CompactionReport compact(final CisStorage<?, ?, ?, ?> storage) {
        final Path storageDir = storage.storageDir();
        final List<RegionCompaction> regions = new ArrayList<>();
        final Set<Path> processedPaths = new HashSet<>();

        compactCachedRegions(storage.drainRegionCache(), regions, processedPaths);
        final int directoryFailures = compactUncachedRegionFiles(storageDir, regions, processedPaths);

        return summarizeCompaction(regions, directoryFailures);
    }

    /**
     * Compacts regions that were already opened by storage and records their paths so disk scanning does not
     * reopen the same region.
     */
    private static void compactCachedRegions(
            final List<RegionFile> cachedFiles,
            final List<RegionCompaction> regions,
            final Set<Path> processedPaths
    ) {
        for (final RegionFile regionFile : cachedFiles) {
            final RegionCompaction result = compactAndCloseRegion(regionFile);
            regions.add(result);
            processedPaths.add(dedupKey(result.path()));
        }
    }

    /**
     * Compacts region files that exist on disk but were not present in the drained region cache.
     *
     * @return number of directory-level failures to include in the aggregate report
     */
    private static int compactUncachedRegionFiles(
            final Path storageDir,
            final List<RegionCompaction> regions,
            final Set<Path> processedPaths
    ) {
        if (!Files.isDirectory(storageDir)) {
            return 0;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, CisRegionPaths.REGION_FILE_GLOB)) {
            for (final Path path : stream) {
                if (!processedPaths.add(dedupKey(path))) {
                    continue;
                }

                regions.add(compactRegionPath(storageDir, path));
            }
            return 0;
        } catch (final IOException e) {
            Chunkis.LOGGER.error("Chunkis: Failed to iterate CIS regions for compaction in {}", storageDir, e);
            return 1;
        }
    }

    /**
     * Opens and compacts one on-disk region that is not currently cached.
     */
    private static RegionCompaction compactRegionPath(final Path storageDir, final Path path) {
        final RegionKey regionKey = CisRegionPaths.parseRegionKey(path);
        if (regionKey == null) {
            // Preserve the old failure shape for non-canonical names matched by the broad discovery glob.
            return new RegionCompaction(path, 0L, 0L, 0L, false);
        }

        try {
            final RegionFile regionFile = new RegionFile(storageDir, regionKey.x(), regionKey.z());
            return compactAndCloseRegion(regionFile);
        } catch (final IOException e) {
            Chunkis.LOGGER.error("Chunkis: Failed to compact CIS region {}", path, e);
            return failedRegionWithCurrentSize(path);
        }
    }

    /**
     * Compacts one region file and guarantees the handle is closed afterward.
     */
    private static RegionCompaction compactAndCloseRegion(final RegionFile regionFile) {
        try {
            return toRegionCompaction(regionFile.compactWithReport());
        } catch (final IOException e) {
            Chunkis.LOGGER.error("Chunkis: Failed to compact CIS region {}", regionFile.path(), e);
            deleteCompactTemp(regionFile.path());
            return failedRegionWithCurrentSize(regionFile.path());
        } finally {
            closeQuietly(regionFile);
        }
    }

    /**
     * Converts the storage-layer compaction report into this command-facing result model.
     */
    private static RegionCompaction toRegionCompaction(final RegionFile.RegionCompactReport report) {
        return new RegionCompaction(
                report.path(),
                report.physicalBytesBefore(),
                report.physicalBytesAfter(),
                report.liveBytes(),
                true
        );
    }

    /**
     * Best-effort cleanup for a temp file left behind by a failed compaction attempt.
     */
    private static void deleteCompactTemp(final Path regionPath) {
        final Path tempPath = compactTempPath(regionPath);
        try {
            Files.deleteIfExists(tempPath);
        } catch (final IOException cleanupError) {
            Chunkis.LOGGER.warn("Chunkis: Failed to delete compact temp for {}", regionPath, cleanupError);
        }
    }

    /** Performs compact temp path. */
    private static Path compactTempPath(final Path regionPath) {
        return regionPath.resolveSibling(regionPath.getFileName()
                .toString() + COMPACT_TEMP_SUFFIX);
    }

    /**
     * Closes a region handle without hiding the original compaction result.
     */
    private static void closeQuietly(final RegionFile regionFile) {
        try {
            regionFile.close();
        } catch (final Exception e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to close CIS region after compaction", e);
        }
    }

    /**
     * Aggregates per-region compaction results into one command-facing summary.
     */
    private static CompactionReport summarizeCompaction(
            final List<RegionCompaction> regions,
            final int extraFailures
    ) {
        long before = 0L;
        long after = 0L;
        long live = 0L;
        int compacted = 0;
        int failed = extraFailures;

        for (final RegionCompaction region : regions) {
            before += region.physicalBytesBefore();
            after += region.physicalBytesAfter();
            live += region.liveBytes();

            if (region.compacted()) {
                compacted++;
            } else {
                failed++;
            }
        }

        return new CompactionReport(
                compacted,
                failed,
                before,
                after,
                live,
                List.copyOf(regions)
        );
    }

    /**
     * Uses a normalized absolute path for deduplication only. The reported path remains unchanged.
     */
    private static Path dedupKey(final Path path) {
        return path.toAbsolutePath()
                .normalize();
    }

    /**
     * Creates a failed region result using the current file size for both physical byte fields.
     */
    private static RegionCompaction failedRegionWithCurrentSize(final Path path) {
        final long size = safeSize(path);
        return new RegionCompaction(path, size, size, 0L, false);
    }

    /**
     * Best-effort file-size lookup used when compaction fails mid-flight.
     */
    private static long safeSize(final Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (final IOException e) {
            return 0L;
        }
    }

    /**
     * Aggregate result for a full-directory compaction pass.
     */
    public record CompactionReport(
            int compactedRegions,
            int failedRegions,
            long physicalBytesBefore,
            long physicalBytesAfter,
            long liveBytes,
            List<RegionCompaction> regions
    ) {

    }

    /**
     * Compaction outcome for one region file.
     */
    public record RegionCompaction(
            Path path,
            long physicalBytesBefore,
            long physicalBytesAfter,
            long liveBytes,
            boolean compacted
    ) {

    }
}
