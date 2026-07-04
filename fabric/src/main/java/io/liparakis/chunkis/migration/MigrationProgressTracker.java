package io.liparakis.chunkis.migration;

import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Tracks the current MCA-to-CIS migration phase for startup messaging.
 */
public final class MigrationProgressTracker {

    private static final AtomicReference<String> STATUS = new AtomicReference<>();
    private static @Nullable StatusListener statusListener;
    private MigrationProgressTracker() {
        throw new AssertionError("Utility class");
    }

    /**
     * Sets the status listener for migration updates.
     *
     * @param listener status listener, or null to clear
     */
    public static void setStatusListener(final @Nullable StatusListener listener) {
        statusListener = listener;
    }

    public static void begin(final String worldId, final int totalRegions) {
        chunkis$updateStatus("Scanning MCA regions for " + worldId + " (" + totalRegions + " region(s))");
    }

    public static void region(
            final String worldId,
            final String sourceRegionFileName,
            final int currentRegion,
            final int totalRegions
    ) {
        final String targetRegionFileName = sourceRegionFileName.replace(".mca", ".cis");
        chunkis$updateStatus(
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
        chunkis$updateStatus("Finished MCA -> CIS migration for " + worldId);
    }

    public static void clear() {
        STATUS.set(null);
    }

    public static @Nullable String getStatus() {
        return STATUS.get();
    }

    private static void chunkis$updateStatus(final @Nullable String status) {
        STATUS.set(status);

        final StatusListener listener = statusListener;
        if (listener != null) {
            listener.onStatusUpdate(status);
        }
    }

    /**
     * Listener interface to receive status updates for client screen rendering.
     */
    public interface StatusListener {

        void onStatusUpdate(@Nullable String status);
    }
}
