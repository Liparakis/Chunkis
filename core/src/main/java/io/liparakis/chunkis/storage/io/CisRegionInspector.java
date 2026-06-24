package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.Chunkis;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads region-level storage diagnostics without going through the higher-level
 * chunk storage facade.
 *
 * <p>This keeps operator-report inspection out of {@link CisStorage} while still
 * reusing {@link RegionFile}'s authoritative space accounting.</p>
 *
 * @author Liparakis
 * @version 1
 */
public final class CisRegionInspector {

    /**
     * Matches canonical Chunkis region filenames and captures region X/Z.
     */
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cis");

    /**
     * Broad discovery glob. The regex above remains the canonical filename validator.
     */
    private static final String REGION_FILE_GLOB = "r.*.*.cis";

    private CisRegionInspector() {
        throw new AssertionError("Utility class");
    }

    /**
     * Opens each region file long enough to report live bytes, free-list state,
     * and reuse counters.
     *
     * @param storageDir directory holding {@code .cis} region files
     * @return immutable region-space snapshots
     */
    public static List<RegionSpaceUsage> inspect(final Path storageDir) {
        if (!Files.isDirectory(storageDir)) {
            return List.of();
        }

        final List<RegionSpaceUsage> regions = new ArrayList<>();
        inspectDirectory(storageDir, regions);
        return List.copyOf(regions);
    }

    /**
     * Iterates the storage directory and appends successfully inspected regions.
     */
    private static void inspectDirectory(final Path storageDir, final List<RegionSpaceUsage> regions) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, REGION_FILE_GLOB)) {
            for (final Path path : stream) {
                inspectRegionPath(storageDir, path, regions);
            }
        } catch (final IOException e) {
            Chunkis.LOGGER.error("Chunkis: Failed to inspect CIS region directory {}", storageDir, e);
        }
    }

    /**
     * Parses and inspects one canonical region path.
     */
    private static void inspectRegionPath(
            final Path storageDir,
            final Path path,
            final List<RegionSpaceUsage> regions
    ) {
        final RegionCoordinates coordinates = parseRegionCoordinates(path);
        if (coordinates == null) {
            return;
        }

        try (RegionFile regionFile = new RegionFile(storageDir, coordinates.x(), coordinates.z())) {
            regions.add(toRegionSpaceUsage(path, regionFile.spaceStats()));
        } catch (final IOException e) {
            Chunkis.LOGGER.error("Chunkis: Failed to inspect CIS region {}", path, e);
        }
    }

    /**
     * Parses canonical {@code r.<x>.<z>.cis} filenames.
     */
    private static RegionCoordinates parseRegionCoordinates(final Path path) {
        final Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }

        try {
            return new RegionCoordinates(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))
            );
        } catch (final NumberFormatException e) {
            // The regex accepts arbitrary digit length; RegionFile coordinates are ints.
            Chunkis.LOGGER.warn("Chunkis: Ignoring CIS region with out-of-range coordinates: {}", path, e);
            return null;
        }
    }

    /**
     * Converts RegionFile's authoritative accounting into the public inspector model.
     */
    private static RegionSpaceUsage toRegionSpaceUsage(
            final Path path,
            final RegionFile.RegionSpaceStats stats
    ) {
        return new RegionSpaceUsage(
                path.getFileName().toString(),
                stats.physicalBytes(),
                stats.liveBytes(),
                stats.reusableBytes(),
                stats.metadataBytes(),
                stats.freeBlockCount(),
                stats.largestFreeBlock(),
                stats.reuseHits(),
                stats.reuseMisses()
        );
    }

    /**
     * Parsed region coordinates from a canonical CIS region filename.
     */
    private record RegionCoordinates(int x, int z) {
    }

    /**
     * Region-level space accounting used by storage diagnostics.
     */
    public record RegionSpaceUsage(
            String name,
            long physicalBytes,
            long liveBytes,
            long reusableBytes,
            int metadataBytes,
            int freeBlockCount,
            int largestFreeBlock,
            long reuseHits,
            long reuseMisses
    ) {
        /**
         * Returns non-live bytes excluding the fixed header and persisted metadata footer.
         */
        public long slackBytes() {
            return Math.max(0L, physicalBytes - RegionFile.HEADER_SIZE - metadataBytes - liveBytes);
        }
    }
}
