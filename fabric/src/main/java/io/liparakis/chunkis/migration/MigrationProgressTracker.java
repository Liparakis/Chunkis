package io.liparakis.chunkis.migration;

import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Tracks the current MCA-to-CIS migration phase for startup messaging.
 */
public final class MigrationProgressTracker {

    private static final AtomicReference<String> STATUS = new AtomicReference<>();

    private MigrationProgressTracker() {
        throw new AssertionError("Utility class");
    }

    public static void begin(final String worldId, final int totalRegions) {
        STATUS.set("Scanning MCA regions for " + worldId + " (" + totalRegions + " region(s))");
    }

    public static void region(
            final String worldId,
            final String sourceRegionFileName,
            final int currentRegion,
            final int totalRegions
    ) {
        final String targetRegionFileName = sourceRegionFileName.replace(".mca", ".cis");
        STATUS.set(
                "Converting "
                        + sourceRegionFileName
                        + " region to "
                        + targetRegionFileName
                        + " region... ("
                        + currentRegion
                        + "/"
                        + totalRegions
                        + ") ["
                        + worldId
                        + "]"
        );
    }

    public static void finish(final String worldId) {
        STATUS.set("Finished MCA -> CIS migration for " + worldId);
    }

    public static void clear() {
        STATUS.set(null);
    }

    public static @Nullable String getStatus() {
        return STATUS.get();
    }
}
