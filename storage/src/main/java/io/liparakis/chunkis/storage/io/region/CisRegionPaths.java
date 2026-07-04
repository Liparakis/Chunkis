package io.liparakis.chunkis.storage.io.region;

import io.liparakis.chunkis.Chunkis;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Naming and parsing helpers for on-disk CIS region files.
 *
 * <p>This keeps file-name conventions in one place so directory scans,
 * inspectors, and compaction helpers all agree on which files represent
 * canonical Chunkis regions.</p>
 */
public final class CisRegionPaths {

    /**
     * Broad glob used to discover candidate region files in a storage directory.
     *
     * <p>The glob is intentionally broader than the strict parser so directory
     * iteration can remain simple while {@link #parseRegionKey(Path)} filters
     * out malformed names.</p>
     */
    public static final String REGION_FILE_GLOB = "r.*.*.cis";

    /**
     * Strict canonical file-name pattern for region coordinates.
     */
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cis");

    private CisRegionPaths() {
        throw new AssertionError("Utility class");
    }

    /**
     * Parses a canonical region file name into its region coordinates.
     *
     * <p>Returns {@code null} when the path does not match the canonical
     * {@code r.<x>.<z>.cis} shape or when either coordinate is outside Java's
     * {@code int} range.</p>
     *
     * @param path candidate region file path
     * @return parsed region key, or {@code null} when the name is invalid
     */
    public static RegionKey parseRegionKey(final Path path) {
        final Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName()
                .toString());
        if (!matcher.matches()) {
            return null;
        }

        try {
            return new RegionKey(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        } catch (final NumberFormatException e) {
            Chunkis.LOGGER.warn("Chunkis: Ignoring CIS region with out-of-range coordinates: {}", path, e);
            return null;
        }
    }
}
