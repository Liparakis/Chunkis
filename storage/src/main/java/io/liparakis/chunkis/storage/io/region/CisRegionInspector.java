package io.liparakis.chunkis.storage.io.region;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.storage.io.CisStorage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads region-level storage diagnostics without going through the higher-level
 * chunk storage facade.
 *
 * <p>This keeps operator-report inspection out of {@link CisStorage} while still
 * reusing {@link RegionFile}'s authoritative space accounting.</p>
 */
public final class CisRegionInspector {

    /**
     * Performs cis region inspector.
     */
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
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, CisRegionPaths.REGION_FILE_GLOB)) {
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
    private static void inspectRegionPath(final Path storageDir, final Path path,
            final List<RegionSpaceUsage> regions) {
        final RegionKey regionKey = CisRegionPaths.parseRegionKey(path);
        if (regionKey == null) {
            return;
        }

        try (RegionFile regionFile = new RegionFile(storageDir, regionKey.x(), regionKey.z())) {
            regions.add(toRegionSpaceUsage(path, regionFile.spaceStats()));
        } catch (final IOException e) {
            Chunkis.LOGGER.error("Chunkis: Failed to inspect CIS region {}", path, e);
        }
    }

    /**
     * Converts RegionFile's authoritative accounting into the public inspector model.
     */
    private static RegionSpaceUsage toRegionSpaceUsage(final Path path, final RegionFile.RegionSpaceStats stats) {
        return new RegionSpaceUsage(path.getFileName()
                .toString(), stats.physicalBytes(), stats.liveBytes(),
                stats.reusableBytes(), stats.metadataBytes(), stats.freeBlockCount(), stats.largestFreeBlock(),
                stats.reuseHits(), stats.reuseMisses());
    }

    /**
     * Region-level space accounting used by storage diagnostics.
     */
    public record RegionSpaceUsage(String name, long physicalBytes, long liveBytes, long reusableBytes,
                                   int metadataBytes, int freeBlockCount, int largestFreeBlock, long reuseHits,
                                   long reuseMisses) {

        /**
         * Returns non-live bytes excluding the fixed header and persisted metadata footer.
         */
        public long slackBytes() {
            return Math.max(0L, physicalBytes - RegionFile.HEADER_SIZE - metadataBytes - liveBytes);
        }
    }
}
