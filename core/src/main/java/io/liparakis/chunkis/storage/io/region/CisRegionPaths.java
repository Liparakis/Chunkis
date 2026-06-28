package io.liparakis.chunkis.storage.io.region;

import io.liparakis.chunkis.Chunkis;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CisRegionPaths {

    public static final String REGION_FILE_GLOB = "r.*.*.cis";

    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cis");

    private CisRegionPaths() {
        throw new AssertionError("Utility class");
    }

    public static RegionKey parseRegionKey(final Path path) {
        final Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }

        try {
            return new RegionKey(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))
            );
        } catch (final NumberFormatException e) {
            Chunkis.LOGGER.warn("Chunkis: Ignoring CIS region with out-of-range coordinates: {}", path, e);
            return null;
        }
    }
}
